# SpinTrack Lab — Project Status

## v0.3.0

Target platform: Android. Primary test device: Samsung Galaxy A56 5G.
Build workflow: GitHub Actions only; Android Studio is not required.

Implemented:
- CameraX live preview and local frame analysis.
- Bright marker/ball tracking on a test rotating disc.
- Tap-to-calibrate disc centre.
- Configurable search ring size, inner boundary and detection sensitivity.
- Optional motion trail.
- Angle, angular velocity, direction, confidence, FPS and radius diagnostics.
- Live angular-velocity graph.
- Start/stop test-session recording.
- Local session history with duration, average/max |omega| and confidence.
- Persistent tracker settings using SharedPreferences.
- GitHub Actions debug APK build.

Safety boundary:
This project is a computer-vision / motion-analysis lab for test discs and educational experiments. It does not predict roulette outcomes or recommend bets.

Next engineering targets:
- Better blob segmentation under reflections.
- Exposure/focus controls where supported by the device.
- Export of test-session telemetry to CSV.
- Device performance mode for lower analysis resolution.
