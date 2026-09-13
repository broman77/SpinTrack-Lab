# SpinTrack Lab v0.3.1

Android computer-vision lab for tracking a bright moving marker/ball on a **test rotating disc**. Primary test device: Samsung Galaxy A56 5G.

This build intentionally focuses on motion tracking and measurement: camera preview, centre calibration, trajectory, angle, angular velocity, confidence, graphing, and local test-session history.

## GitHub-only build

Android Studio is not required.

1. Upload the project files to the repository root.
2. Commit to `main`.
3. Open **Actions → Build Android APK**.
4. Wait for the green check mark.
5. Open the completed run and download the artifact **SpinTrackLab-v0.3.1-debug-apk**.
6. Extract the artifact ZIP and install `SpinTrackLab-v0.3.1-debug.apk` on the Android phone.

## CI toolchain

The workflow uses a deliberately conservative, mutually compatible toolchain:

- Android 15 / API 35
- Android Build Tools 35.0.0
- Android Gradle Plugin 8.9.2
- Gradle 8.11.1
- JDK 17
- Kotlin 2.2.21
- CameraX 1.4.2

The previous API 37 installation step was removed because that SDK package was the point where the GitHub Actions run failed.

## Current features

- CameraX rear-camera preview
- Tap-to-calibrate test-disc centre
- Bright-marker tracking in an annular search region
- Short motion trail
- Angle and angular velocity measurement
- CW/CCW direction indication
- FPS and confidence
- Live angular-velocity graph
- Adjustable tracker sensitivity/search region
- Local test-session history
