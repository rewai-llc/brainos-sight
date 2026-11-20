# BrainOS Sight

**BrainOS Sight is not a mobility aid. It makes no medical claims and is not a medical device.** Do not rely on it for navigation, obstacle or hazard detection, crossing streets, or any safety-critical decision.

BrainOS Sight is an Android app for people who are blind or have low vision. It is used today by 4 to 6 active testers who are blind or low-vision.

## Hardware

Sight runs on an Android phone (Android 10 or newer) with an off-the-shelf bone-conduction headset that has a built-in camera, the E12. The headset takes photos when asked over Bluetooth, and Sight downloads full-size photos from the headset's own local Wi-Fi.

The code also supports the BrainOS Cam, a Bluetooth LE camera board (Seeed XIAO ESP32S3 Sense) that can be worn on a headset. Its firmware is not included in this repository.

## What it does

The wearer presses a button, taps the headset, or asks by voice. Sight takes one photo and reads text aloud using Google ML Kit text recognition, with spoken framing hints such as "turn slightly right."

It can also describe the scene, or answer a spoken question about the photo, using Gemma 3n, a small open model that runs on the phone through MediaPipe. The model is instructed never to claim a path is safe or clear.

## Privacy and on-device processing

- **Processing is on-device.** Text recognition (ML Kit, bundled model) and scene descriptions (Gemma 3n through MediaPipe) run on the phone. Photos and answers are never sent to the internet.
- **No video is saved.** Sight works with single photos, not video.
- **Photos:** the E12 headset saves each photo on its own storage. Sight downloads it over the headset's local Wi-Fi and then asks the headset to delete it. In release builds, Sight keeps photos in memory only and does not write them to the phone's storage. Debug builds keep the most recent headset photo and its raw Bluetooth data in the app's private folder for troubleshooting. **[OPEN: confirm which build type testers run. The only Sight APKs found on the build PC are debug builds.]**
- **Speech in:** Android speech recognition, on the phone where the phone supports it. If a phone can't recognize speech by itself, spoken questions stay off unless the user turns on "Allow online speech recognition" in Sight's settings (off by default). The system speech service, not Sight, may then send the spoken question (never the photo) online.
- **Speech out:** Android text-to-speech, fully offline when an offline voice is installed.
- `allowBackup` is false, and logs carry only short status tags.

## Network and permissions

Sight has Android's INTERNET permission. Android needs it for any network connection, including the local Wi-Fi link to the headset. Sight talks only to the headset, never to the internet:

- The phone joins the headset's Wi-Fi as a local-only link (the network request removes the internet capability), so only Sight's requests use it. In the headset's Wi-Fi Direct mode the phone joins the headset's Wi-Fi Direct group.
- The code refuses any address except the headset's two local addresses, `192.168.169.1` (hotspot mode) and `192.168.49.207` (Wi-Fi Direct mode). Plain HTTP is allowed only to those two addresses (`sight/src/main/res/xml/network_security_config.xml`); all other cleartext traffic is blocked.
- The headset's Wi-Fi password in `camera-link` (`12345678`) is the manufacturer's fixed default, not a secret.
- **ML Kit usage reporting is removed.** ML Kit includes Google's data-transport library, which by default sends usage and diagnostic data (device model, app version, performance metrics) to Google. Sight's manifest removes that library's backend discovery service and upload schedulers (`tools:node="remove"`), so it has no backend to send to. These components are absent from the merged manifest of the 0.5.0-sight build. **[OPEN: not yet confirmed by watching the phone's network traffic.]**

Permissions in the 0.5.0-sight build (from `aapt dump permissions`):

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` | Local Wi-Fi link to the headset only |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES` (never used for location) | Joining the headset's Wi-Fi or Wi-Fi Direct group |
| `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (never used for location); `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` on Android 11 and older only | Headset and camera link over Bluetooth |
| `RECORD_AUDIO` | Spoken questions |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK`, `POST_NOTIFICATIONS` | Keeping the headset connected with the screen off |
| `com.brainos.sight.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Added by AndroidX; internal to the app |

## Accessibility

The target is WCAG 2.2 AA for the app's interface.

## Model

The Gemma model is **not** included in this repository. The user imports the model file (`gemma.task`) into the app's private storage.

## Status

Early version, `0.5.0-sight` (version code 22). Sight is a separate app from BrainOS, with its own app ID (`com.brainos.sight`) and launcher icon. It does not include or depend on BrainOS's cloud assistant.

A debug build of 0.5.0-sight was built on Nov 10, 2026 from the same source in the larger BrainOS project. **[OPEN: which version and build type testers run.]**

## Building

- `sight/`: the Sight app module (package `com.brainos.sight`), with unit tests.
- `camera-link/`: the shared camera library (E12 headset over Bluetooth and Wi-Fi, and the BrainOS Cam board), with unit tests.
- Root Gradle files: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`. Toolchain: Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.0.21, Java 17, compile and target SDK 35, min SDK 29.

Open the folder in Android Studio, or run `gradle :sight:assembleDebug` with Gradle 8.13.

**[OPEN: building from this repository layout has not been verified. The tester build was produced inside the larger BrainOS project, which includes a third module not published here. The Gradle wrapper is not included yet.]**

## License

Copyright 2026 REWAI, LLC. Licensed under the Apache License, Version 2.0; see `LICENSE` and `NOTICE`. Apache 2.0 covers only the source in this repository.

## Third-party notices

- **Gemma:** the Gemma model is provided by Google under the [Gemma Terms of Use](https://ai.google.dev/gemma/terms) and its Prohibited Use Policy. It is not distributed here.
- **Google ML Kit:** used under the [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms). See Google's [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) for what ML Kit collects by default.
- **MediaPipe:** MediaPipe is licensed under the [Apache License 2.0](https://github.com/google-ai-edge/mediapipe/blob/master/LICENSE).
