# SpinTrack Lab v0.3.0

Android computer-vision lab for tracking a bright moving marker/ball on a test rotating disc.
Primary test device: Samsung Galaxy A56 5G.

## GitHub-only build

No Android Studio is required.

1. Upload the contents of this folder to a GitHub repository.
2. Push/commit to `main` or `master`.
3. Open **Actions → Build Android APK**.
4. Wait for the workflow to finish.
5. Download artifact **SpinTrackLab-v0.3.0-debug-apk**.
6. Extract and install **SpinTrackLab-v0.3.0-debug.apk** on the Android phone.

## What v0.3 contains

- CameraX preview and on-device frame analysis.
- Tap-to-calibrate disc centre.
- Bright-object tracking in a configurable annular search region.
- Angle, angular velocity, direction, confidence and FPS.
- Live angular-velocity graph.
- Tracker settings: ring size, inner boundary, sensitivity, trail visibility.
- Local test-session recording and history.

The app performs motion analysis locally on the device. It is intended for test discs and educational CV experiments, not prediction of gambling outcomes or betting guidance.
