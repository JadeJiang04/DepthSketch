# Device integration checks

These checks use actual ONNX models, Android views, touch events, project serialization and the system wallpaper integration. They are not model-quality benchmarks or cross-device speed claims.

**Use an emulator or a dedicated test device.** Editor integration checks replace the current test canvas; `additions` also applies a live wallpaper on the test device. Do not run them on an installation containing artwork or a wallpaper you want to preserve.

```shell
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

On Windows, replace `./gradlew` with `.\gradlew.bat`. If multiple devices are connected, add `-s DEVICE_SERIAL` to each adb command. Keep the test device awake and unlocked.

## Checks

```shell
# Drawing, real depth inference, selection, transforms, project I/O and export
adb shell am instrument -w studio.depthsketch.test/studio.depthsketch.SmokeInstrumentation

# Existing point-selection workflow, object editing and live wallpaper confirmation
adb shell am instrument -w -e additions true studio.depthsketch.test/studio.depthsketch.SmokeInstrumentation

# Prepared image import: real depth, exact alpha, disconnected detail, history and save/load
adb shell am instrument -w -e materials true studio.depthsketch.test/studio.depthsketch.SmokeInstrumentation

# Pan/zoom, compact inspector, feathering and independent composite re-estimation
adb shell am instrument -w -e interactions true studio.depthsketch.test/studio.depthsketch.SmokeInstrumentation
```

The `materials` check returns a synthetic PNG through a monitored system-picker intent, then runs real depth inference. It verifies semitransparent alpha, a separate one-pixel component, no segmentation activity, aligned depth, undo/redo, project round trips, opaque JPEG import and rejection of a fully transparent image.

Completed v0.3.3 checks are recorded in [`releases/v0.3.3/`](releases/v0.3.3/). The tested APK's SHA-256 is included there and in the release download. The current change passed the default, `additions` and `materials` checks; `interactions` was previously covered for v0.3.2 and was not rerun for this material-import-only change. Lint reported 0 errors and 21 warnings.

Some optional scene-preview and latency diagnostics require external local fixtures. Those photographs are not distributed in this repository. The default, `additions`, `materials` and `interactions` checks do not require those private fixture files.
