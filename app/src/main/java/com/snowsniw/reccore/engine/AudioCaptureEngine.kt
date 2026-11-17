package com.snowsniw.reccore.engine

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import com.snowsniw.reccore.data.AudioSourceMode
import com.snowsniw.reccore.data.RecPreferences
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class AudioCaptureEngine(
    private val mediaProjection: MediaProjection,
    private val onFormatChanged: (MediaFormat) -> Unit,
    private val onEncodedFrame: (ByteBuffer, MediaCodec.BufferInfo) -> Unit
) {
    private var internalRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var captureThread: Thread? = null

    @Volatile
    private var isRunning = false

    @Volatile
    var isPaused = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val mode = RecPreferences.audioSource
        if (mode == AudioSourceMode.NONE) return false

        val sampleRate = RecPreferences.audioSampleRate
        val isStereo = RecPreferences.audioStereo
        val channelCount = if (isStereo) 2 else 1
        val channelMask = if (isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBufferSize <= 0) return false
        val bufferSize = minBufferSize * 2

        val audioFormat = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        if (mode == AudioSourceMode.INTERNAL || mode == AudioSourceMode.INTERNAL_AND_MIC) {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            internalRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        }

        if (mode == AudioSourceMode.MIC || mode == AudioSourceMode.INTERNAL_AND_MIC) {
            micRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()
        }

        val codecFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, RecPreferences.audioCodec.profile)
            setInteger(MediaFormat.KEY_BIT_RATE, RecPreferences.audioBitrateKbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize * 2)
        }

        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(codecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        internalRecord?.startRecording()
        micRecord?.startRecording()
        isRunning = true

        captureThread = Thread({
            processAudioLoop(bufferSize, sampleRate, channelCount)
        }, "RecCore-AudioThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        return true
    }

    private fun processAudioLoop(bufferSize: Int, sampleRate: Int, channelCount: Int) {
        val codec = audioCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val internalBuf = ByteArray(bufferSize)
        val micBuf = ByteArray(bufferSize)
        val mixedBuf = ByteArray(bufferSize)

        val internalGain = RecPreferences.internalVolumePercent / 100f
        val micGain = RecPreferences.micVolumePercent / 100f

        var totalSamplesWritten = 0L

        while (isRunning) {
            val intRead = internalRecord?.read(internalBuf, 0, bufferSize) ?: 0
            val micRead = micRecord?.read(micBuf, 0, bufferSize) ?: 0

            if (isPaused) {
                continue
            }

            val bytesToEncode = when {
                internalRecord != null && micRecord != null -> {
                    val validBytes = min(max(0, intRead), max(0, micRead))
                    if (validBytes > 0) {
                        mixPcm16(internalBuf, micBuf, mixedBuf, validBytes, internalGain, micGain)
                    }
                    validBytes
                }
                internalRecord != null -> {
                    if (intRead > 0) {
                        applyGainPcm16(internalBuf, mixedBuf, intRead, internalGain)
                    }
                    intRead
                }
                micRecord != null -> {
                    if (micRead > 0) {
                        applyGainPcm16(micBuf, mixedBuf, micRead, micGain)
                    }
                    micRead
                }
                else -> 0
            }

            if (bytesToEncode > 0) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(mixedBuf, 0, bytesToEncode)

                    val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate
                    val samplesInChunk = bytesToEncode / (2 * channelCount)
                    totalSamplesWritten += samplesInChunk

                    codec.queueInputBuffer(inputIndex, 0, bytesToEncode, ptsUs, 0)
                }
            }

            drainCodec(codec, bufferInfo, endOfStream = false)
        }

        val endInputIndex = codec.dequeueInputBuffer(10_000)
        if (endInputIndex >= 0) {
            codec.queueInputBuffer(endInputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drainCodec(codec, bufferInfo, endOfStream = true)
    }

    private fun drainCodec(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onFormatChanged(codec.outputFormat)
                }
                outIndex >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outIndex)
                    if (encodedData != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        onEncodedFrame(encodedData, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
                else -> break
            }
        }
    }

    private fun mixPcm16(
        srcA: ByteArray,
        srcB: ByteArray,
        dst: ByteArray,
        byteCount: Int,
        gainA: Float,
        gainB: Float
    ) {
        val shortCount = byteCount / 2
        val bufA = ByteBuffer.wrap(srcA).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val bufB = ByteBuffer.wrap(srcB).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        for (i in 0 until shortCount) {
            val mixed = (bufA.get(i) * gainA + bufB.get(i) * gainB).toInt()
            out.put(i, mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
    }

    private fun applyGainPcm16(src: ByteArray, dst: ByteArray, byteCount: Int, gain: Float) {
        if (gain == 1.0f) {
            System.arraycopy(src, 0, dst, 0, byteCount)
            return
        }
        val shortCount = byteCount / 2
        val inBuf = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val outBuf = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        for (i in 0 until shortCount) {
            val scaled = (inBuf.get(i) * gain).toInt()
            outBuf.put(i, scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
    }

    fun stop() {
        isRunning = false
        try {
            captureThread?.join(1000)
        } catch (_: Exception) {
        }
        captureThread = null

        runCatching { internalRecord?.stop() }
        runCatching { internalRecord?.release() }
        internalRecord = null

        runCatching { micRecord?.stop() }
        runCatching { micRecord?.release() }
        micRecord = null

        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioCodec = null
    }
}
 