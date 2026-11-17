package com.snowsniw.reccore.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.snowsniw.reccore.MainActivity
import com.snowsniw.reccore.data.AudioSourceMode
import com.snowsniw.reccore.data.OrientationMode
import com.snowsniw.reccore.data.RecPreferences
import com.snowsniw.reccore.data.ResolutionPreset
import com.snowsniw.reccore.engine.AudioCaptureEngine
import com.snowsniw.reccore.engine.VideoCaptureEngine
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class RecordStatus {
    IDLE, COUNTDOWN, RECORDING, PAUSED
}

class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.snowsniw.reccore.ACTION_START"
        const val ACTION_PAUSE_RESUME = "com.snowsniw.reccore.ACTION_PAUSE_RESUME"
        const val ACTION_STOP = "com.snowsniw.reccore.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        private const val CHANNEL_ID = "reccore_capture_channel"
        private const val NOTIFICATION_ID = 2001

        val statusLiveData = MutableLiveData(RecordStatus.IDLE)
        val elapsedSecondsLiveData = MutableLiveData(0L)
        val estimatedBytesLiveData = MutableLiveData(0L)
        val countdownLiveData = MutableLiveData(0)

        var onRecordingFinishedCallback: (() -> Unit)? = null
    }

    private data class PendingSample(
        val isVideo: Boolean,
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var videoEngine: VideoCaptureEngine? = null
    private var audioEngine: AudioCaptureEngine? = null
    private var mediaMuxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var videoUri: Uri? = null

    private val muxerLock = Any()
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var hasAudioTrack = false
    private var muxerStarted = false
    private val pendingSamples = mutableListOf<PendingSample>()

    @Volatile
    private var isCapturing = false

    @Volatile
    private var isPaused = false

    private var writtenBytes = 0L
    private var activeSeconds = 0L

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isCapturing && !isPaused) {
                activeSeconds++
                elapsedSecondsLiveData.postValue(activeSeconds)
                estimatedBytesLiveData.postValue(writtenBytes)
                updateNotification()
            }
            if (isCapturing) {
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && RecPreferences.stopOnScreenOff) {
                stopRecording()
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopRecording()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (statusLiveData.value != RecordStatus.IDLE) return START_NOT_STICKY

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                if (resultCode == Activity.RESULT_OK && data != null) {
                    startForegroundProperly("Preparing capture...")
                    val countdown = RecPreferences.countdownSeconds
                    if (countdown > 0) {
                        runCountdownAndStart(countdown, resultCode, data)
                    } else {
                        beginCapture(resultCode, data)
                    }
                } else {
                    stopSelf()
                }
            }

            ACTION_PAUSE_RESUME -> togglePauseResume()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun runCountdownAndStart(seconds: Int, resultCode: Int, data: Intent) {
        statusLiveData.postValue(RecordStatus.COUNTDOWN)
        var remaining = seconds
        countdownLiveData.postValue(remaining)

        val countdownRunnable = object : Runnable {
            override fun run() {
                if (statusLiveData.value != RecordStatus.COUNTDOWN) return
                remaining--
                countdownLiveData.postValue(remaining)
                if (remaining > 0) {
                    updateNotification("Starting in ${remaining}s...")
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    beginCapture(resultCode, data)
                }
            }
        }
        mainHandler.postDelayed(countdownRunnable, 1000L)
    }

    private fun beginCapture(resultCode: Int, data: Intent) {
        try {
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(projectionCallback, mainHandler)

            val (width, height) = calculateVideoResolution()
            val density = resources.displayMetrics.densityDpi

            setupMediaStoreFile()
            val descriptor = pfd?.fileDescriptor ?: throw IllegalStateException("FileDescriptor is null")
            mediaMuxer = MediaMuxer(descriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val projection = mediaProjection!!
            videoEngine = VideoCaptureEngine(
                mediaProjection = projection,
                width = width,
                height = height,
                densityDpi = density,
                handler = mainHandler,
                onFormatChanged = { format -> onTrackFormatReady(isVideo = true, format = format) },
                onEncodedFrame = { buffer, info -> writeSample(isVideo = true, buffer = buffer, info = info) }
            ).also { it.start() }

            hasAudioTrack = RecPreferences.audioSource != AudioSourceMode.NONE
            if (hasAudioTrack) {
                audioEngine = AudioCaptureEngine(
                    mediaProjection = projection,
                    onFormatChanged = { format -> onTrackFormatReady(isVideo = false, format = format) },
                    onEncodedFrame = { buffer, info -> writeSample(isVideo = false, buffer = buffer, info = info) }
                )
                if (audioEngine?.start() != true) {
                    hasAudioTrack = false
                }
            }

            isCapturing = true
            isPaused = false
            writtenBytes = 0L
            activeSeconds = 0L

            statusLiveData.postValue(RecordStatus.RECORDING)
            elapsedSecondsLiveData.postValue(0L)
            estimatedBytesLiveData.postValue(0L)
            updateNotification()

            mainHandler.postDelayed(timerRunnable, 1000L)
        } catch (e: Exception) {
            e.printStackTrace()
            mainHandler.post {
                Toast.makeText(this, "Capture error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            cleanup(discardFile = true)
            stopSelf()
        }
    }

    private fun onTrackFormatReady(isVideo: Boolean, format: MediaFormat) {
        synchronized(muxerLock) {
            val muxer = mediaMuxer ?: return
            if (isVideo && videoTrackIndex == -1) {
                videoTrackIndex = muxer.addTrack(format)
            } else if (!isVideo && audioTrackIndex == -1) {
                audioTrackIndex = muxer.addTrack(format)
            }

            val readyToStart = videoTrackIndex != -1 && (!hasAudioTrack || audioTrackIndex != -1)
            if (readyToStart && !muxerStarted) {
                muxer.start()
                muxerStarted = true

                for (sample in pendingSamples) {
                    val track = if (sample.isVideo) videoTrackIndex else audioTrackIndex
                    if (track != -1) {
                        val info = MediaCodec.BufferInfo().apply {
                            set(0, sample.data.size, sample.presentationTimeUs, sample.flags)
                        }
                        muxer.writeSampleData(track, ByteBuffer.wrap(sample.data), info)
                        writtenBytes += sample.data.size
                    }
                }
                pendingSamples.clear()
            }
        }
    }

    private fun writeSample(isVideo: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            val muxer = mediaMuxer ?: return
            if (!muxerStarted) {
                if (pendingSamples.size < 120) {
                    val copy = ByteArray(info.size)
                    buffer.get(copy)
                    pendingSamples.add(PendingSample(isVideo, copy, info.presentationTimeUs, info.flags))
                }
                return
            }

            val trackIndex = if (isVideo) videoTrackIndex else audioTrackIndex
            if (trackIndex != -1) {
                muxer.writeSampleData(trackIndex, buffer, info)
                writtenBytes += info.size
            }
        }
    }

    private fun togglePauseResume() {
        if (!isCapturing) return
        isPaused = !isPaused
        videoEngine?.setPaused(isPaused)
        audioEngine?.isPaused = isPaused
        statusLiveData.postValue(if (isPaused) RecordStatus.PAUSED else RecordStatus.RECORDING)
        updateNotification()
    }

    private fun stopRecording() {
        if (statusLiveData.value == RecordStatus.IDLE && mediaMuxer == null) {
            stopSelf()
            return
        }

        mainHandler.removeCallbacksAndMessages(null)
        isCapturing = false
        isPaused = false

        cleanup(discardFile = false)

        statusLiveData.postValue(RecordStatus.IDLE)
        elapsedSecondsLiveData.postValue(0L)
        estimatedBytesLiveData.postValue(0L)

        mainHandler.post {
            Toast.makeText(this, "Saved to Movies/RecCore", Toast.LENGTH_SHORT).show()
            onRecordingFinishedCallback?.invoke()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup(discardFile: Boolean) {
        runCatching { videoEngine?.stop() }
        videoEngine = null

        runCatching { audioEngine?.stop() }
        audioEngine = null

        synchronized(muxerLock) {
            if (muxerStarted) {
                runCatching { mediaMuxer?.stop() }
            }
            runCatching { mediaMuxer?.release() }
            mediaMuxer = null
            muxerStarted = false
            videoTrackIndex = -1
            audioTrackIndex = -1
            pendingSamples.clear()
        }

        mediaProjection?.unregisterCallback(projectionCallback)
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        runCatching { pfd?.close() }
        pfd = null

        videoUri?.let { uri ->
            if (discardFile) {
                contentResolver.delete(uri, null, null)
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
            }
        }
        videoUri = null
    }

    private fun setupMediaStoreFile() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "RecCore_$timestamp.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/RecCore")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore returned null")
        videoUri = uri
        pfd = contentResolver.openFileDescriptor(uri, "w")
    }

    private fun calculateVideoResolution(): Pair<Int, Int> {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        } else {
            val dm = resources.displayMetrics
            android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }

        var screenW = bounds.width()
        var screenH = bounds.height()

        when (RecPreferences.orientationMode) {
            OrientationMode.PORTRAIT -> if (screenW > screenH) {
                val tmp = screenW
                screenW = screenH
                screenH = tmp
            }
            OrientationMode.LANDSCAPE -> if (screenH > screenW) {
                val tmp = screenW
                screenW = screenH
                screenH = tmp
            }
            OrientationMode.AUTO -> Unit
        }

        val preset = RecPreferences.resolutionPreset
        val (scaledW, scaledH) = if (preset == ResolutionPreset.NATIVE) {
            Pair(screenW, screenH)
        } else {
            val shortSide = min(screenW, screenH)
            if (shortSide <= preset.targetShortSide) {
                Pair(screenW, screenH)
            } else {
                val scale = preset.targetShortSide.toFloat() / shortSide.toFloat()
                Pair((screenW * scale).toInt(), (screenH * scale).toInt())
            }
        }

        val alignedW = max(16, scaledW and 15.inv())
        val alignedH = max(16, scaledH and 15.inv())
        return Pair(alignedW, alignedH)
    }

    private fun startForegroundProperly(initialText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        startForeground(
            NOTIFICATION_ID,
            buildNotification(initialText),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun updateNotification(overrideText: String? = null) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(overrideText))
    }

    private fun buildNotification(overrideText: String? = null): Notification {
        val pauseResumeIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_PAUSE_RESUME },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mins = activeSeconds / 60
        val secs = activeSeconds % 60
        val timeStr = String.format(Locale.US, "%02d:%02d", mins, secs)

        val title = when (statusLiveData.value) {
            RecordStatus.PAUSED -> "Recording Paused • $timeStr"
            RecordStatus.COUNTDOWN -> "RecCore"
            else -> "Recording • $timeStr"
        }

        val text = overrideText ?: "${RecPreferences.videoCodec.shortName} • ${RecPreferences.fps} FPS • ${RecPreferences.audioSource.shortName}"
        val pauseLabel = if (isPaused) "Resume" else "Pause"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent)
            .setOngoing(true)

        if (isCapturing) {
            builder.addAction(android.R.drawable.ic_media_pause, pauseLabel, pauseResumeIntent)
        }
        builder.addAction(android.R.drawable.ic_delete, "Stop", stopIntent)

        return builder.build()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        if (isCapturing) {
            stopRecording()
        }
        super.onDestroy()
    }
}
