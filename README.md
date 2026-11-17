# RecCore

![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![UI](https://img.shields.io/badge/UI-Material%20You-6750A4?style=flat-square&logo=materialdesign&logoColor=white)
![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square)

A lightweight, ad-free, hardware-accelerated screen recorder for Android built directly on native system media APIs (`MediaProjection`, `MediaCodec`, `MediaMuxer`, and `AudioPlaybackCapture`). Designed with a clean **Material You** interface and deep encoder customization to adapt to any device.

---

## Features

### Audio Engine & Real-Time Mixer
* **Flexible Audio Sources:** Capture **Internal Audio** (system & games), **Microphone**, **Internal + Microphone** simultaneously, or record muted.
* **Real-Time 16-bit PCM Mixing:** Blends system playback and microphone input on the fly without external native libraries.
* **Independent Channel Gain:** Separate volume sliders (`0% – 150%`) for internal audio and microphone to balance game sound and voice.
* **Audio Encoder Tuning:** Choose between `AAC-LC` and `HE-AAC`, `44.1 kHz` or `48 kHz` sample rates, `96–320 kbps` bitrates, and `Mono` or `Stereo` channels.

### Hardware-Accelerated Video
* **Smart Codec Detection:** Scans `MediaCodecList` on startup to detect hardware-accelerated encoders available on your SoC (`H.264 / AVC`, `H.265 / HEVC`, `AV1`, `VP9`) with automatic fallback to `H.264`.
* **Resolution Scaling:** Record in `Native (100%)`, `1080p`, `720p`, or `540p` with automatic 16-pixel macroblock alignment to prevent hardware encoder crashes.
* **Frame Rate & Bitrate Control:** Supports `24`, `30`, `60`, `90`, and `120 FPS`, `2–50 Mbps` video bitrate, and `VBR` (Variable) or `CBR` (Constant) rate control modes.
* **Orientation Lock:** `Auto`, `Portrait`, or `Landscape`.

### Controls, Presets & Workflow
* **One-Tap Profiles:** Pre-configured **Gaming**, **Tutorial**, and **Low Spec** presets for instant setup.
* **Seamless Pause & Resume:** Adjusts presentation timestamps (`PTS`) dynamically so paused segments leave zero audio-video desync.
* **Countdown & Auto-Stop:** Configurable start timer (`Off`, `3s`, `5s`, `10s`) and automatic recording finalization when locking the screen.
* **Live Telemetry & Gallery:** Real-time duration and file-size counter on the home screen, plus an integrated `MediaStore` browser (`Movies/RecCore`) with thumbnails, playback, sharing, and deletion.
* **Material You Design:** Dynamic wallpaper-based color theming (`DynamicColors`) with zero ads or tracking.

---

## Tech Stack

* **Language:** Kotlin
* **Core Media APIs:** `MediaProjection`, `VirtualDisplay`, `MediaCodec`, `MediaMuxer`, `AudioPlaybackCaptureConfiguration`, `AudioRecord`
* **Storage:** Android `MediaStore` API (`Movies/RecCore`)
* **UI:** Material Components for Android (Material 3 / Dynamic Colors), ViewBinding, RecyclerView

---

## Requirements & Notes

* **Android 10 (API 29) or higher** is required for `AudioPlaybackCapture` support.
* Some third-party apps (such as banking apps or DRM-protected streaming services) explicitly block internal audio capture via `ALLOW_CAPTURE_BY_NONE`; in those cases, Android outputs silence on the internal audio track.

---

## Building from Source

1. Clone the repository:
   ```bash
   git clone [https://github.com/Snowsniw371/RecCore.git](https://github.com/Snowsniw371/RecCore.git)
   ```
2. Open the project in **Android Studio** or **Code on the Go**.
3. Build the debug or release APK:
   ```bash
   ./gradlew assembleDebug
   ```

---

## Roadmap

* [ ] Quick Settings Tile for one-tap capture from the notification shade
* [ ] Floating control pill overlay with drag-and-drop positioning
* [ ] Custom user-defined presets

---

## License

Distributed under the **GNU General Public License v3.0 (GPL-3.0)**. See `LICENSE` for more information.
