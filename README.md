# AndroidSurround

Multi-channel surround sound audio player for Android. Play local files, stream URLs, and output to multiple USB sound cards simultaneously with real-time upmixing DSP.

Inspired by PipeWire's `virtualsurround.conf` and `upmix.conf` — but for Android.

## Features

- **Multi-device output** — Use multiple USB sound cards as a combined surround system
- **Configurable channel layouts** — 2.0, 2.1, 4.0, 5.1, 7.1
- **Upmixing DSP** — Convert stereo to surround in real-time:
  - PSD (Phase Shift Delay) — all-pass filter matrix
  - Simple Matrix — basic channel mapping
  - Dolby Pro Logic II-like — active steering
- **Playback sources**:
  - Local audio files (MP3, FLAC, WAV, OGG, etc.)
  - Streamable URLs (HTTP/HTTPS)
  - Any content via Android share intent
- **Root mode** — Optional system-wide audio interception (via `su` + ALSA loopback)
- **Native audio engine** — C++ DSP via JNI for low-latency processing
- **Material 3 UI** — Modern Jetpack Compose interface

## How it works

```
[File/URL/Stream] → [Media3 ExoPlayer] → [PCM Decode]
                                         → [Upmix DSP (C++/JNI)]
                                         → [Multi-AudioTrack Router]
                                         → [USB Device 1 (FL/FR)]
                                         → [USB Device 2 (FC/LFE)]
                                         → [USB Device 3 (SL/SR)]
                                         → ...
```

The app decodes audio via ExoPlayer, routes the PCM data through a C++ upmix processor (PSD, Simple, or Dolby matrix), then distributes channels across selected USB audio devices using Android's `AudioTrack` with `setPreferredDevice()`.

## Requirements

- **Android 8.0+** (API 26)
- USB sound cards (OTG adapter needed for most devices)
- Root: Optional — enables system audio interception (not required for in-app playback)

## Building

### Prerequisites
- Android Studio Hedgehog (2023.1.1+) or command-line:
- JDK 17+
- Android SDK 35
- NDK 27+

### From command line
```bash
git clone https://github.com/NaievetestV2/Upmixing-Android-Audio
cd Upmixing-Android-Audio
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`

### From Android Studio
Open the project directory, sync Gradle, and run on device.

## Usage

1. **Connect** USB sound cards via OTG hub
2. **Open** AndroidSurround
3. **Refresh devices** — tap the refresh icon in the Devices card
4. **Select devices** — choose which USB outputs to use
5. **Choose layout** — tap the Channel Layout card to select 2.0–7.1
6. **Configure upmix** — tap Upmixing to enable/configure DSP
7. **Play** — open a file or paste a stream URL
8. **Start Engine** — tap the FAB to begin multi-channel output

### Device → Channel mapping

With multiple devices, channels are distributed round-robin:

| Devices | 7.1 mapping |
|---------|------------|
| 1       | All channels → single device |
| 2       | Device 1: FL/FR/FC/LFE, Device 2: SL/SR/RL/RR |
| 3+      | Even split across devices |

## Upmix Configuration Reference

The upmix settings mirror PipeWire's `upmix.conf` parameters:

| Setting | PipeWire key | Default | Description |
|---------|-------------|---------|-------------|
| Method | `channelmix.upmix-method` | PSD | Upmix algorithm |
| Mix LFE | `channelmix.mix-lfe` | true | Generate subwoofer channel |
| LFE Cutoff | `channelmix.lfe-cutoff` | 150 Hz | Low-pass filter for LFE |
| FC Cutoff | `channelmix.fc-cutoff` | 12000 Hz | Low-pass for center channel |
| Rear Delay | `channelmix.rear-delay` | 12 ms | Delay for rear/side channels |
| LFE Level | — | 1.0x | Subwoofer gain |

## Root Features

If the device is rooted, the app can:
- Load `snd-aloop` kernel module for ALSA loopback
- Route system audio through the upmix pipeline
- Use `tinymix` to configure hardware routing

Root is **entirely optional** — all core features work without it.

## Architecture

```
com.androidsurround/
├── model/           # Data models
│   ├── AudioDevice.kt
│   ├── ChannelLayout.kt
│   └── UpmixConfig.kt
├── audio/           # Audio engine
│   ├── DeviceManager.kt      # USB device detection
│   ├── UpmixProcessor.kt     # Kotlin DSP fallback
│   ├── MultiSinkManager.kt   # Multi-device AudioTrack router
│   └── AudioEngine.kt        # Pipeline coordinator
├── playback/        # Media playback
│   └── MediaPlayerManager.kt # ExoPlayer wrapper
├── root/            # Root helpers
│   └── RootShell.kt
└── ui/              # Jetpack Compose UI
    ├── MainScreen.kt
    ├── DeviceSelectorSheet.kt
    ├── ChannelConfigDialog.kt
    ├── UpmixSettingsDialog.kt
    └── MediaPlayerBar.kt
native/
└── upmix_dsp.cpp    # C++ DSP (AAudio + upmix)
```

## License

MIT
