package com.snowsniw.reccore.engine

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.view.Surface
import com.snowsniw.reccore.data.RecPreferences
import com.snowsniw.reccore.model.CodecDetector
import com.snowsniw.reccore.model.VideoCodecType
import java.nio.ByteBuffer
import kotlin.math.max

class VideoCaptureEngine(
    private val mediaProjection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val handler: Handler,
    private val onFormatChanged: (MediaFormat) -> Unit,
    private val onEncodedFrame: (ByteBuffer, MediaCodec.BufferInfo) -> Unit
) {
    private var videoCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainThread: Thread? = null

    @Volatile
    private var isRunning = false

    @Volatile
    var isPaused = false
        private set

    private var pauseStartUs = 0L
    private var totalPausedUs = 0L
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L

    fun start() {
        val preferredType = RecPreferences.videoCodec
        val fps = RecPreferences.fps
        val bitrate = RecPreferences.videoBitrateMbps * 1_000_000
        val bitrateMode = RecPreferences.bitrateMode.modeValue

        try {
            configureCodec(preferredType, fps, bitrate, bitrateMode)
        } catch (_: Exception) {
            releaseCodec()
            configureCodec(VideoCodecType.AVC, fps, bitrate, bitrateMode)
        }

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "RecCoreDisplay",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            handler
        )

        firstPtsUs = -1L
        lastPtsUs = -1L
        totalPausedUs = 0L
        isPaused = false
        isRunning = true

        drainThread = Thread({ drainLoop() }, "RecCore-VideoThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun configureCodec(
        codecType: VideoCodecType,
        fps: Int,
        bitrate: Int,
        bitrateMode: Int
    ) {
        val cap = CodecDetector.getSupportedVideoCodecs().firstOrNull { it.type == codecType }
        val codec = if (cap != null) {
            MediaCodec.createByCodecName(cap.codecName)
        } else {
            MediaCodec.createEncoderByType(codecType.mimeType)
        }

        val format = MediaFormat.createVideoFormat(codecType.mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)

            val encoderCaps = runCatching {
                codec.codecInfo.getCapabilitiesForType(codecType.mimeType)?.encoderCapabilities
            }.getOrNull()
            if (encoderCaps == null || encoderCaps.isBitrateModeSupported(bitrateMode)) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
            }
        }

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        videoCodec = codec
    }

    private fun drainLoop() {
        val codec = videoCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onFormatChanged(codec.outputFormat)
                }
                outIndex >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outIndex)
                    if (encodedData != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0) {
                        if (!isPaused) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)

                            if (firstPtsUs < 0L) {
                                firstPtsUs = bufferInfo.presentationTimeUs
                            }
                            val relativePts = bufferInfo.presentationTimeUs - firstPtsUs - totalPausedUs
                            val adjustedPts = max(lastPtsUs + 1L, relativePts)
                            lastPtsUs = adjustedPts
                            bufferInfo.presentationTimeUs = adjustedPts

                            onEncodedFrame(encodedData, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    fun setPaused(paused: Boolean) {
        if (isPaused == paused) return
        if (paused) {
            isPaused = true
            pauseStartUs = System.nanoTime() / 1000L
        } else {
            totalPausedUs += (System.nanoTime() / 1000L) - pauseStartUs
            isPaused = false
            runCatching {
                val params = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
                videoCodec?.setParameters(params)
            }
        }
    }

    fun stop() {
        isRunning = false
        runCatching { drainThread?.join(1000) }
        drainThread = null

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        releaseCodec()
    }

    private fun releaseCodec() {
        runCatching { videoCodec?.signalEndOfInputStream() }
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        videoCodec = null

        runCatching { inputSurface?.release() }
        inputSurface = null
    }
}
