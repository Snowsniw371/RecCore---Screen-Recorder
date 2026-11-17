package com.snowsniw.reccore.data

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodecInfo
import com.snowsniw.reccore.model.VideoCodecType

enum class AudioSourceMode(val displayName: String, val shortName: String) {
    NONE("No Audio", "Muted"),
    INTERNAL("Internal Audio", "Internal"),
    MIC("Microphone", "Mic"),
    INTERNAL_AND_MIC("Internal + Mic", "Int + Mic")
}

enum class ResolutionPreset(val targetShortSide: Int, val displayName: String) {
    NATIVE(0, "Native (100%)"),
    P1080(1080, "1080p (FHD)"),
    P720(720, "720p (HD)"),
    P540(540, "540p (Low)")
}

enum class BitrateControlMode(val modeValue: Int, val displayName: String) {
    VBR(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, "VBR (Variable)"),
    CBR(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, "CBR (Constant)")
}

enum class OrientationMode(val displayName: String) {
    AUTO("Auto"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
}

enum class AudioCodecType(val displayName: String, val profile: Int) {
    AAC_LC("AAC-LC", MediaCodecInfo.CodecProfileLevel.AACObjectLC),
    HE_AAC("HE-AAC", MediaCodecInfo.CodecProfileLevel.AACObjectHE)
}

object RecPreferences {
    private const val PREFS_NAME = "reccore_settings"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Video Settings ---
    var videoCodec: VideoCodecType
        get() = runCatching {
            VideoCodecType.valueOf(prefs.getString("video_codec", VideoCodecType.AVC.name)!!)
        }.getOrDefault(VideoCodecType.AVC)
        set(value) = prefs.edit().putString("video_codec", value.name).apply()

    var resolutionPreset: ResolutionPreset
        get() = runCatching {
            ResolutionPreset.valueOf(prefs.getString("resolution", ResolutionPreset.NATIVE.name)!!)
        }.getOrDefault(ResolutionPreset.NATIVE)
        set(value) = prefs.edit().putString("resolution", value.name).apply()

    var fps: Int
        get() = prefs.getInt("fps", 60)
        set(value) = prefs.edit().putInt("fps", value).apply()

    var videoBitrateMbps: Int
        get() = prefs.getInt("video_bitrate_mbps", 12)
        set(value) = prefs.edit().putInt("video_bitrate_mbps", value).apply()

    var bitrateMode: BitrateControlMode
        get() = runCatching {
            BitrateControlMode.valueOf(prefs.getString("bitrate_mode", BitrateControlMode.VBR.name)!!)
        }.getOrDefault(BitrateControlMode.VBR)
        set(value) = prefs.edit().putString("bitrate_mode", value.name).apply()

    var orientationMode: OrientationMode
        get() = runCatching {
            OrientationMode.valueOf(prefs.getString("orientation", OrientationMode.AUTO.name)!!)
        }.getOrDefault(OrientationMode.AUTO)
        set(value) = prefs.edit().putString("orientation", value.name).apply()

    // --- Audio Settings ---
    var audioSource: AudioSourceMode
        get() = runCatching {
            AudioSourceMode.valueOf(prefs.getString("audio_source", AudioSourceMode.INTERNAL.name)!!)
        }.getOrDefault(AudioSourceMode.INTERNAL)
        set(value) = prefs.edit().putString("audio_source", value.name).apply()

    var internalVolumePercent: Int
        get() = prefs.getInt("internal_vol", 100)
        set(value) = prefs.edit().putInt("internal_vol", value.coerceIn(0, 150)).apply()

    var micVolumePercent: Int
        get() = prefs.getInt("mic_vol", 100)
        set(value) = prefs.edit().putInt("mic_vol", value.coerceIn(0, 150)).apply()

    var audioCodec: AudioCodecType
        get() = runCatching {
            AudioCodecType.valueOf(prefs.getString("audio_codec", AudioCodecType.AAC_LC.name)!!)
        }.getOrDefault(AudioCodecType.AAC_LC)
        set(value) = prefs.edit().putString("audio_codec", value.name).apply()

    var audioSampleRate: Int
        get() = prefs.getInt("audio_sample_rate", 48000)
        set(value) = prefs.edit().putInt("audio_sample_rate", value).apply()

    var audioBitrateKbps: Int
        get() = prefs.getInt("audio_bitrate_kbps", 192)
        set(value) = prefs.edit().putInt("audio_bitrate_kbps", value).apply()

    var audioStereo: Boolean
        get() = prefs.getBoolean("audio_stereo", true)
        set(value) = prefs.edit().putBoolean("audio_stereo", value).apply()

    // --- Controls Settings ---
    var floatingControlsEnabled: Boolean
        get() = prefs.getBoolean("floating_controls", false)
        set(value) = prefs.edit().putBoolean("floating_controls", value).apply()

    var countdownSeconds: Int
        get() = prefs.getInt("countdown_sec", 3)
        set(value) = prefs.edit().putInt("countdown_sec", value).apply()

    var stopOnScreenOff: Boolean
        get() = prefs.getBoolean("stop_screen_off", false)
        set(value) = prefs.edit().putBoolean("stop_screen_off", value).apply()

    // --- Presets ---
    fun applyPreset(presetName: String) {
        when (presetName) {
            "GAMING" -> {
                videoCodec = VideoCodecType.HEVC
                resolutionPreset = ResolutionPreset.NATIVE
                fps = 60
                videoBitrateMbps = 20
                bitrateMode = BitrateControlMode.VBR
                audioSource = AudioSourceMode.INTERNAL
                audioBitrateKbps = 192
            }
            "TUTORIAL" -> {
                videoCodec = VideoCodecType.AVC
                resolutionPreset = ResolutionPreset.P1080
                fps = 30
                videoBitrateMbps = 10
                bitrateMode = BitrateControlMode.VBR
                audioSource = AudioSourceMode.INTERNAL_AND_MIC
                internalVolumePercent = 70
                micVolumePercent = 120
                audioBitrateKbps = 192
            }
            "LOW_SPEC" -> {
                videoCodec = VideoCodecType.AVC
                resolutionPreset = ResolutionPreset.P720
                fps = 30
                videoBitrateMbps = 6
                bitrateMode = BitrateControlMode.VBR
                audioSource = AudioSourceMode.INTERNAL
                audioBitrateKbps = 128
            }
        }
    }
}

