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

## USB Sound Card Compatibility

### How Android sees USB audio devices

When you plug a USB sound card into an Android device (via OTG), Android's audio HAL enumerates it as an output device accessible through `AudioManager.getDevices()`. The app uses `AudioDeviceInfo` to discover devices and `AudioTrack.setPreferredDevice()` to route audio to a specific card.

Android treats each USB sound card as an independent output sink. The app creates **one `AudioTrack` per device**, each bound to a specific card via its `AudioDeviceInfo` ID.

### How multi-device output works

Standard Android only routes to one sink at a time. This app works around that by:

1. Creating **separate `AudioTrack` streams** for each selected USB device
2. Assigning each track to a specific device via `setPreferredDevice()`
3. Splitting the upmixed multi-channel audio into per-device channel groups
4. Writing each group to its corresponding track in parallel

### USB hardware requirements

| Requirement | Details |
|-------------|---------|
| **OTG support** | Your Android device must support USB Host Mode (OTG). Most devices since Android 6.0+ support this. |
| **USB hub** | To use multiple cards, you need a **Powered USB-C OTG hub** (passive splitters won't work — USB audio cards draw power) |
| **USB audio class** | Cards using **UAC 1.0** (USB Audio Class 1.0) work best. UAC 2.0 is supported on Android 11+ but varies by vendor |
| **Sample rate** | Most USB cards report 44100/48000 Hz. Android will default to 48000 Hz. Cards that only support odd rates may fail |
| **Channel count** | The app queries `AudioDeviceInfo.getChannelCounts()`. Output is limited to the max channel count of each device (typically stereo) |

### Known incompatibilities

| Issue | Cause | Workaround |
|-------|-------|------------|
| **Some USB DACs appear as "headset" not "USB device"** | Device reports as `TYPE_USB_HEADSET` instead of `TYPE_USB_DEVICE` | The app handles both types — they will appear in the device list |
| **Audio only plays from one card** | Android's audio policy may block simultaneous output to multiple USB devices on some ROMs | Try different USB order, or use a powered hub. Some Xiaomi/Huawei devices restrict multi-device output |
| **No devices detected** | OTG not supported, or USB card not in UAC 1.0 mode | Check `dmesg` or `lsusb` via Termux. Try a different USB card |
| **Crackling/distorted audio** | Buffer underrun — USB card can't keep up with the write rate | The app dynamically adjusts buffer sizes. Try different buffer settings or a faster USB card |
| **Device disconnects randomly** | Power starvation — USB card drawing too much current | Use a **powered** USB hub (not a passive splitter) |
| **App crashes on device selection** | `setPreferredDevice()` is supported on Android 8.0+ (API 26) but some vendor ROMs have broken implementations | The app catches these errors gracefully. Update to a modern ROM if possible |
| **Bluetooth + USB simultaneously** | Android typically only allows one audio route at a time | Deselect Bluetooth devices if using USB. Mixing BT + USB is not supported |
| **Very high latency** | Each `AudioTrack` adds its own buffer latency. More devices = more latency | Reduce buffer sizes in settings. Latency is typically 100-300ms |
| **Some USB 3.0 audio cards** | Android's USB host stack has poor UAC 2.0 support on many devices | Use UAC 1.0 mode if your card supports it, or use a USB 2.0 card |

### Recommended hardware

| Type | Example | Notes |
|------|---------|-------|
| **USB stereo adapter** | "USB Audio Adapter" (CM108/CM119 chip) | Cheap, reliable, UAC 1.0. Great for multi-device setups |
| **USB sound card** | "7.1 USB External Sound Card" (C-Media chip) | Single-device 7.1 output via multiple 3.5mm jacks |
| **USB DAC** | FiiO, Topping, etc. | Higher quality but typically stereo-only |
| **Powered USB hub** | Anker/UGREEN 4-port USB-C hub | **Required** for multiple cards. Provides power to all cards |
| **OTG adapter** | USB-C to USB-A female | For devices with a single USB-C port |

### Testing your setup

1. Plug in the USB sound card(s)
2. Open AndroidSurround and tap **Refresh devices**
3. If the card appears in the list with "(USB)" tag, it's detected
4. Tap to select it
5. Play a test file to verify output

## Requirements

- **Android 8.0+** (API 26) — required for `AudioTrack.setPreferredDevice()`
- **USB Host Mode** (OTG) — all modern Android devices support this
- **USB sound card(s)** — UAC 1.0 recommended
- **Powered USB hub** — needed for multiple cards
- **Root**: Optional — enables system audio interception (not required for in-app playback)

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
