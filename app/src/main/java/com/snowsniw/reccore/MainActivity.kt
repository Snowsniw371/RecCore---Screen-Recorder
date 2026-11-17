package com.snowsniw.reccore

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.snowsniw.reccore.data.AudioCodecType
import com.snowsniw.reccore.data.AudioSourceMode
import com.snowsniw.reccore.data.BitrateControlMode
import com.snowsniw.reccore.data.OrientationMode
import com.snowsniw.reccore.data.RecPreferences
import com.snowsniw.reccore.data.ResolutionPreset
import com.snowsniw.reccore.data.VideoItem
import com.snowsniw.reccore.data.VideoRepository
import com.snowsniw.reccore.databinding.ActivityMainBinding
import com.snowsniw.reccore.model.CodecDetector
import com.snowsniw.reccore.model.VideoCodecType
import com.snowsniw.reccore.service.RecordStatus
import com.snowsniw.reccore.service.ScreenRecordService
import com.snowsniw.reccore.ui.VideoAdapter
import java.util.Locale
import android.media.projection.MediaProjectionConfig


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var videoAdapter: VideoAdapter
    private var isBindingUi = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRecordService.EXTRA_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val needsMic = RecPreferences.audioSource == AudioSourceMode.MIC ||
            RecPreferences.audioSource == AudioSourceMode.INTERNAL_AND_MIC
        val micGranted = !needsMic || ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (micGranted) {
            requestScreenCapture()
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = Settings.canDrawOverlays(this)
        RecPreferences.floatingControlsEnabled = granted
        syncControlsTab()
        updateRecordSummary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        setupRecordTab()
        setupVideoTab()
        setupAudioTab()
        setupControlsTab()
        observeServiceState()

        refreshAllUi()
        loadVideos()
    }

    override fun onResume() {
        super.onResume()
        loadVideos()
    }

    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            binding.tabRecord.root.visibility = if (item.itemId == R.id.nav_record) View.VISIBLE else View.GONE
            binding.tabVideo.root.visibility = if (item.itemId == R.id.nav_video) View.VISIBLE else View.GONE
            binding.tabAudio.root.visibility = if (item.itemId == R.id.nav_audio) View.VISIBLE else View.GONE
            binding.tabControls.root.visibility = if (item.itemId == R.id.nav_controls) View.VISIBLE else View.GONE

            binding.topAppBar.title = when (item.itemId) {
                R.id.nav_video -> "Video Settings"
                R.id.nav_audio -> "Audio Settings"
                R.id.nav_controls -> "Controls & Presets"
                else -> "RecCore"
            }
            true
        }
    }

    private fun setupRecordTab() {
        videoAdapter = VideoAdapter(
            onPlayClick = { openVideo(it) },
            onShareClick = { shareVideo(it) },
            onDeleteClick = { confirmDeleteVideo(it) }
        )
        binding.tabRecord.rvVideos.layoutManager = LinearLayoutManager(this)
        binding.tabRecord.rvVideos.adapter = videoAdapter

        binding.tabRecord.cardQuickAudio.setOnClickListener {
            if (ScreenRecordService.statusLiveData.value != RecordStatus.IDLE) return@setOnClickListener
            val values = AudioSourceMode.entries
            val nextIndex = (values.indexOf(RecPreferences.audioSource) + 1) % values.size
            RecPreferences.audioSource = values[nextIndex]
            syncAudioTab()
            updateRecordSummary()
        }

        binding.tabRecord.cardQuickOverlay.setOnClickListener {
            toggleFloatingControls(!RecPreferences.floatingControlsEnabled)
        }

        binding.fabRecord.setOnClickListener {
            when (ScreenRecordService.statusLiveData.value) {
                RecordStatus.IDLE -> checkPermissionsAndStart()
                else -> sendServiceAction(ScreenRecordService.ACTION_STOP)
            }
        }

        binding.fabPauseResume.setOnClickListener {
            sendServiceAction(ScreenRecordService.ACTION_PAUSE_RESUME)
        }

        ScreenRecordService.onRecordingFinishedCallback = {
            loadVideos()
        }
    }

    private fun setupVideoTab() {
        val supported = CodecDetector.getSupportedVideoCodecs()
        val infoLines = supported.joinToString(" • ") {
            "${it.type.shortName} (${if (it.isHardware) "HW" else "SW"})"
        }
        binding.tabVideo.tvCodecInfo.text = "Detected: $infoLines"

        binding.tabVideo.rgVideoCodec.removeAllViews()
        for (cap in supported) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${cap.type.displayName} ${if (cap.isHardware) "[HW]" else "[SW]"}"
                tag = cap.type
            }
            binding.tabVideo.rgVideoCodec.addView(rb)
        }

        binding.tabVideo.rgVideoCodec.setOnCheckedChangeListener { group, checkedId ->
            if (isBindingUi) return@setOnCheckedChangeListener
            val rb = group.findViewById<RadioButton>(checkedId)
            val type = rb?.tag as? VideoCodecType ?: return@setOnCheckedChangeListener
            RecPreferences.videoCodec = type
            updateRecordSummary()
        }

        binding.tabVideo.rgResolution.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingUi) return@setOnCheckedChangeListener
            RecPreferences.resolutionPreset = when (checkedId) {
                R.id.rbRes1080 -> ResolutionPreset.P1080
                R.id.rbRes720 -> ResolutionPreset.P720
                R.id.rbRes540 -> ResolutionPreset.P540
                else -> ResolutionPreset.NATIVE
            }
            updateRecordSummary()
        }

        val refreshRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.refreshRate?.toInt() ?: 60
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.refreshRate.toInt()
        }
        binding.tabVideo.tvDisplayRefreshHint.text = "Display refresh rate: ${refreshRate} Hz"

        binding.tabVideo.rgFps.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingUi) return@setOnCheckedChangeListener
            RecPreferences.fps = when (checkedId) {
                R.id.rbFps24 -> 24
                R.id.rbFps30 -> 30
                R.id.rbFps90 -> 90
                R.id.rbFps120 -> 120
                else -> 60
            }
            updateRecordSummary()
        }

        binding.tabVideo.sliderBitrate.addOnChangeListener { _, value, fromUser ->
            val mbps = value.toInt()
            binding.tabVideo.tvBitrateValue.text = "$mbps Mbps"
            if (fromUser && !isBindingUi) {
                RecPreferences.videoBitrateMbps = mbps
                updateRecordSummary()
            }
        }

        binding.tabVideo.rgBitrateMode.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingUi) return@setOnCheckedChangeListener
            RecPreferences.bitrateMode = if (checkedId == R.id.rbModeCbr) {
                BitrateControlMode.CBR
            } else {
                BitrateControlMode.VBR
            }
            updateRecordSummary()
        }

        binding.tabVideo.rgOrientation.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingUi) return@setOnCheckedChangeListener
            RecPreferences.orientationMode = when (checkedId) {
                R.id.rbOrientPortrait -> OrientationMode.PORTRAIT
                R.id.rbOrientLandscape -> OrientationMode.LANDSCAPE
                else -> OrientationMode.AUTO
            }
        }
    }

    private fun setupAudioTab() {
        binding.tabAudio.rgAudioSource.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingUi) return@setOnCheckedChangeListener
            RecPreferences.audioSource = when (checkedId) {
                R.id.rbAudioNone -> AudioSourceMode.NONE
                R.id.rbAudioMic -> AudioSourceMode.MIC
                R.id.rbAudioBoth -> AudioSourceMode.INTERNAL_AND_MIC
                else -> AudioSourceMode.INTERNAL
            }
            updateRecordSummary()
        }

        binding.tabAudio.sliderInternalVol.addOnChangeListener { _, value, fromUser ->
            val pct = value.toInt()
            binding.tabAudio.tvInternalVolValue.text = "$pct%"
            if (fromUser && !isBindingUi) {
                RecPreferences.internalVolumePercent = pct
            }
        }

        binding.tabAudio.sliderMicVol.addOnChangeListener { _, value, fromUser ->
            val pct = value.toInt()
            binding.tabAudio.tvMicVolValue.text = "$pct%"
            if (fromUser && !isBindingUi) {
                RecPreferences.micVolumePercent = pct
            }
        }

        binding.tabAudio.rgAudioCodec.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingUi) return@setOnCheckedChangeListener
            RecPreferences.audioCodec = if (checkedId == R.id.rbHeAac) {
                AudioCodecType.HE_AAC
            } else {
                AudioCodecType.AAC_LC
            }
        }

        binding.tabAudio.rgSampleRate.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingUi) return@setOnCheckedChangeListener
            RecPreferences.audioSampleRate = if (checkedId == R.id.rbRate44) 44100 else 48000
        }

        binding.tabAudio.rgAudioBitrate.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingUi) return@setOnCheckedChangeListener
            RecPreferences.audioBitrateKbps = when (checkedId) {
                R.id.rbAudBit96 -> 96
                R.id.rbAudBit128 -> 128
                320 -> R.id.rbAudBit320
                else -> 192
            }
        }

        binding.tabAudio.rgChannels.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingUi) return@setOnCheckedChangeListener
            RecPreferences.audioStereo = checkedId != R.id.rbChanMono
        }
    }

    private fun setupControlsTab() {
        binding.tabControls.btnPresetGaming.setOnClickListener {
            RecPreferences.applyPreset("GAMING")
            refreshAllUi()
            Toast.makeText(this, "Gaming preset applied", Toast.LENGTH_SHORT).show()
        }

        binding.tabControls.btnPresetTutorial.setOnClickListener {
            RecPreferences.applyPreset("TUTORIAL")
            refreshAllUi()
            Toast.makeText(this, "Tutorial preset applied", Toast.LENGTH_SHORT).show()
        }

        binding.tabControls.btnPresetLowSpec.setOnClickListener {
            RecPreferences.applyPreset("LOW_SPEC")
            refreshAllUi()
            Toast.makeText(this, "Low Spec preset applied", Toast.LENGTH_SHORT).show()
        }

        binding.tabControls.switchFloating.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            toggleFloatingControls(isChecked)
        }

        binding.tabControls.switchScreenOff.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            RecPreferences.stopOnScreenOff = isChecked
        }

        binding.tabControls.rgCountdown.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingUi) return@setOnCheckedChangeListener
            RecPreferences.countdownSeconds = when (checkedId) {
                R.id.rbCount0 -> 0
                R.id.rbCount5 -> 5
                R.id.rbCount10 -> 10
                else -> 3
            }
        }
    }

    private fun toggleFloatingControls(enable: Boolean) {
        if (enable && !Settings.canDrawOverlays(this)) {
            syncControlsTab()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlaySettingsLauncher.launch(intent)
            return
        }
        RecPreferences.floatingControlsEnabled = enable
        syncControlsTab()
        updateRecordSummary()
    }

    private fun refreshAllUi() {
        syncVideoTab()
        syncAudioTab()
        syncControlsTab()
        updateRecordSummary()
    }

    private fun syncVideoTab() {
        isBindingUi = true
        val currentCodec = RecPreferences.videoCodec
        for (i in 0 until binding.tabVideo.rgVideoCodec.childCount) {
            val rb = binding.tabVideo.rgVideoCodec.getChildAt(i) as? RadioButton
            if (rb?.tag == currentCodec) {
                rb.isChecked = true
                break
            }
        }

        binding.tabVideo.rgResolution.check(
            when (RecPreferences.resolutionPreset) {
                ResolutionPreset.P1080 -> R.id.rbRes1080
                ResolutionPreset.P720 -> R.id.rbRes720
                ResolutionPreset.P540 -> R.id.rbRes540
                ResolutionPreset.NATIVE -> R.id.rbResNative
            }
        )

        binding.tabVideo.rgFps.check(
            when (RecPreferences.fps) {
                24 -> R.id.rbFps24
                30 -> R.id.rbFps30
                90 -> R.id.rbFps90
                120 -> R.id.rbFps120
                else -> R.id.rbFps60
            }
        )

        binding.tabVideo.sliderBitrate.value = RecPreferences.videoBitrateMbps.toFloat().coerceIn(2f, 50f)
        binding.tabVideo.tvBitrateValue.text = "${RecPreferences.videoBitrateMbps} Mbps"

        binding.tabVideo.rgBitrateMode.check(
            if (RecPreferences.bitrateMode == BitrateControlMode.CBR) R.id.rbModeCbr else R.id.rbModeVbr
        )

        binding.tabVideo.rgOrientation.check(
            when (RecPreferences.orientationMode) {
                OrientationMode.PORTRAIT -> R.id.rbOrientPortrait
                OrientationMode.LANDSCAPE -> R.id.rbOrientLandscape
                OrientationMode.AUTO -> R.id.rbOrientAuto
            }
        )
        isBindingUi = false
    }

    private fun syncAudioTab() {
        isBindingUi = true
        binding.tabAudio.rgAudioSource.check(
            when (RecPreferences.audioSource) {
                AudioSourceMode.NONE -> R.id.rbAudioNone
                AudioSourceMode.MIC -> R.id.rbAudioMic
                AudioSourceMode.INTERNAL_AND_MIC -> R.id.rbAudioBoth
                AudioSourceMode.INTERNAL -> R.id.rbAudioInternal
            }
        )

        binding.tabAudio.sliderInternalVol.value = RecPreferences.internalVolumePercent.toFloat()
        binding.tabAudio.tvInternalVolValue.text = "${RecPreferences.internalVolumePercent}%"

        binding.tabAudio.sliderMicVol.value = RecPreferences.micVolumePercent.toFloat()
        binding.tabAudio.tvMicVolValue.text = "${RecPreferences.micVolumePercent}%"

        binding.tabAudio.rgAudioCodec.check(
            if (RecPreferences.audioCodec == AudioCodecType.HE_AAC) R.id.rbHeAac else R.id.rbAacLc
        )

        binding.tabAudio.rgSampleRate.check(
            if (RecPreferences.audioSampleRate == 44100) R.id.rbRate44 else R.id.rbRate48
        )

        binding.tabAudio.rgAudioBitrate.check(
            when (RecPreferences.audioBitrateKbps) {
                96 -> R.id.rbAudBit96
                128 -> R.id.rbAudBit128
                320 -> R.id.rbAudBit320
                else -> R.id.rbAudBit192
            }
        )

        binding.tabAudio.rgChannels.check(
            if (RecPreferences.audioStereo) R.id.rbChanStereo else R.id.rbChanMono
        )
        isBindingUi = false
    }

    private fun syncControlsTab() {
        isBindingUi = true
        val overlayAllowed = Settings.canDrawOverlays(this)
        if (!overlayAllowed && RecPreferences.floatingControlsEnabled) {
            RecPreferences.floatingControlsEnabled = false
        }
        binding.tabControls.switchFloating.isChecked = RecPreferences.floatingControlsEnabled
        binding.tabControls.switchScreenOff.isChecked = RecPreferences.stopOnScreenOff

        binding.tabControls.rgCountdown.check(
            when (RecPreferences.countdownSeconds) {
                0 -> R.id.rbCount0
                5 -> R.id.rbCount5
                10 -> R.id.rbCount10
                else -> R.id.rbCount3
            }
        )
        isBindingUi = false
    }

    private fun updateRecordSummary() {
        val codec = RecPreferences.videoCodec.shortName
        val res = when (RecPreferences.resolutionPreset) {
            ResolutionPreset.NATIVE -> "Native"
            ResolutionPreset.P1080 -> "1080p"
            ResolutionPreset.P720 -> "720p"
            ResolutionPreset.P540 -> "540p"
        }
        val fps = "${RecPreferences.fps} FPS"
        val bitrate = "${RecPreferences.videoBitrateMbps} Mbps"
        val audio = RecPreferences.audioSource.shortName

        binding.tabRecord.tvConfigSummary.text = "$codec • $res • $fps • $bitrate • $audio"
        binding.tabRecord.tvQuickAudioValue.text = RecPreferences.audioSource.displayName
        binding.tabRecord.tvQuickOverlayValue.text = if (RecPreferences.floatingControlsEnabled) "Enabled" else "Off"
    }

    private fun observeServiceState() {
        ScreenRecordService.statusLiveData.observe(this) { status ->
            when (status) {
                RecordStatus.IDLE -> {
                    binding.tabRecord.tvRecordStatus.text = "Ready to capture"
                    binding.tabRecord.tvTimer.text = "00:00"
                    binding.tabRecord.tvFileSize.text = "0.0 MB"
                    binding.fabRecord.text = "Start Recording"
                    binding.fabRecord.setIconResource(R.drawable.ic_tab_record)
                    binding.fabPauseResume.visibility = View.GONE
                }
                RecordStatus.COUNTDOWN -> {
                    binding.tabRecord.tvRecordStatus.text = "Starting soon..."
                    binding.fabRecord.text = "Cancel"
                    binding.fabRecord.setIconResource(R.drawable.ic_delete)
                    binding.fabPauseResume.visibility = View.GONE
                }
                RecordStatus.RECORDING -> {
                    binding.tabRecord.tvRecordStatus.text = "Recording active"
                    binding.fabRecord.text = "Stop"
                    binding.fabRecord.setIconResource(R.drawable.ic_delete)
                    binding.fabPauseResume.visibility = View.VISIBLE
                    binding.fabPauseResume.setImageResource(android.R.drawable.ic_media_pause)
                }
                RecordStatus.PAUSED -> {
                    binding.tabRecord.tvRecordStatus.text = "Recording paused"
                    binding.fabRecord.text = "Stop"
                    binding.fabRecord.setIconResource(R.drawable.ic_delete)
                    binding.fabPauseResume.visibility = View.VISIBLE
                    binding.fabPauseResume.setImageResource(R.drawable.ic_play)
                }
                null -> Unit
            }
        }

        ScreenRecordService.countdownLiveData.observe(this) { sec ->
            if (ScreenRecordService.statusLiveData.value == RecordStatus.COUNTDOWN && sec > 0) {
                binding.tabRecord.tvTimer.text = "00:0$sec"
            }
        }

        ScreenRecordService.elapsedSecondsLiveData.observe(this) { totalSec ->
            val mins = totalSec / 60
            val secs = totalSec % 60
            binding.tabRecord.tvTimer.text = String.format(Locale.US, "%02d:%02d", mins, secs)
        }

        ScreenRecordService.estimatedBytesLiveData.observe(this) { bytes ->
            val mb = bytes / (1024f * 1024f)
            binding.tabRecord.tvFileSize.text = String.format(Locale.US, "%.1f MB", mb)
        }
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        val needsMic = RecPreferences.audioSource == AudioSourceMode.MIC ||
            RecPreferences.audioSource == AudioSourceMode.INTERNAL_AND_MIC

        if (needsMic && ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            requestScreenCapture()
        }
    }

        private fun requestScreenCapture() {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(captureIntent)
    }


    private fun sendServiceAction(action: String) {
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    private fun loadVideos() {
        val videos = VideoRepository.loadRecordings(this)
        videoAdapter.submitList(videos)
        binding.tabRecord.tvEmptyVideos.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openVideo(item: VideoItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }

    private fun shareVideo(item: VideoItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share recording"))
    }

    private fun confirmDeleteVideo(item: VideoItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete recording?")
            .setMessage(item.name)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (VideoRepository.deleteRecording(this, item.uri)) {
                    loadVideos()
                }
            }
            .show()
    }
}