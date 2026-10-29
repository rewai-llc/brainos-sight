# BrainOS Sight

**BrainOS Sight is not a mobility aid. It is not a medical device.**

BrainOS Sight is an Android app for people who are blind or have low vision.

## What it does

The wearer presses the button on a small Bluetooth LE camera (the BrainOS Cam, an ESP32S3-based board) or asks by voice. The app fetches one photo into phone memory and reads text aloud using Google ML Kit text recognition (bundled model), with spoken framing hints such as "turn slightly right."

It can also describe the scene, or answer a spoken question about the photo, using Gemma 3n, a small open model run on the phone through MediaPipe. The Gemma prompt tells the model never to claim a path is safe or clear, and states "you are not a mobility aid."

## Speech

- **Speech in:** Android speech recognition, on-device where the phone supports it. The intended setup is a Pixel-class phone with on-device recognition.
- **Speech out:** Android text-to-speech in the earbud. Text-to-speech is fully offline only when an offline voice is installed.

## Offline design

- The app has no INTERNET permission and makes no network calls.
- Its only permissions are microphone, Bluetooth, notifications, and foreground service.
- The system speech recognizer is outside the app and could use the network on some phones.
- Photos are kept in memory only; the code does not save them.
- `allowBackup` is false.
- Logs carry only short status tags.

## Model

The Gemma model is **not** included in this repository. The user imports the model file (`gemma.task`) into the app's private storage. Gemma has its own license terms, separate from Apache 2.0.

## Status

Early version `0.1.0-sight`. This repository contains source only. The code has not been built or tested in this repository, and it has no root Gradle files: it is a module of the larger BrainOS Live project and is published as source for review. The camera firmware lives elsewhere and is not included.

## Layout

- `sight/` — the Sight Android module source (package `com.brainos.sight`), including `LogicTest.kt`.

## License

Copyright 2026 REWAI, LLC. Licensed under the Apache License, Version 2.0; see `LICENSE` and `NOTICE`.

Apache 2.0 covers only the Sight source in this repository. Google ML Kit and MediaPipe are Google libraries under their own terms, and Gemma is under its own license terms.
