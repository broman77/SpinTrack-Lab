# Project status — v0.3.1

## Build repair

The GitHub Actions build pipeline was moved off API 37 and onto the stable Android 15 / API 35 toolchain.

Pinned versions:
- compileSdk / targetSdk: 35
- Build Tools: 35.0.0
- AGP: 8.9.2
- Gradle: 8.11.1
- JDK: 17
- Kotlin: 2.2.21

The workflow now verifies that the APK actually exists before uploading the artifact and prints a SHA-256 checksum for the produced APK.

## App scope

SpinTrack Lab is maintained as a generic computer-vision and motion-analysis application for a test rotating disc. It does not include gambling-outcome prediction.
