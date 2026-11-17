package com.snowsniw.reccore.model

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

enum class VideoCodecType(val mimeType: String, val displayName: String, val shortName: String) {
    AVC(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264 (AVC)", "H.264"),
    HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "H.265 (HEVC)", "HEVC"),
    AV1(MediaFormat.MIMETYPE_VIDEO_AV1, "AV1", "AV1"),
    VP9(MediaFormat.MIMETYPE_VIDEO_VP9, "VP9", "VP9")
}

data class CodecCapability(
    val type: VideoCodecType,
    val codecName: String,
    val isHardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxBitrate: Int
)

object CodecDetector {

    fun getSupportedVideoCodecs(): List<CodecCapability> {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val found = mutableMapOf<VideoCodecType, CodecCapability>()

        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue

            for (type in VideoCodecType.entries) {
                if (info.supportedTypes.any { it.equals(type.mimeType, ignoreCase = true) }) {
                    val caps = try {
                        info.getCapabilitiesForType(type.mimeType)?.videoCapabilities
                    } catch (_: Exception) {
                        null
                    } ?: continue

                    val isHw = info.isHardwareAccelerated
                    val existing = found[type]

                    // Отдаём приоритет аппаратному энкодеру над программным
                    if (existing == null || (!existing.isHardware && isHw)) {
                        found[type] = CodecCapability(
                            type = type,
                            codecName = info.name,
                            isHardware = isHw,
                            maxWidth = caps.supportedWidths.upper,
                            maxHeight = caps.supportedHeights.upper,
                            maxBitrate = caps.bitrateRange.upper
                        )
                    }
                }
            }
        }

        return VideoCodecType.entries.mapNotNull { found[it] }
    }

    fun isCodecSupported(type: VideoCodecType): Boolean {
        return getSupportedVideoCodecs().any { it.type == type }
    }
}
