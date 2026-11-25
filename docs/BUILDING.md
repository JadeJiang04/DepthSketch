# Build DepthSketch

## Requirements

- JDK 17 or 21.
- Android SDK platform 34 and build-tools 34.0.0; set `ANDROID_HOME` or create `local.properties` with `sdk.dir=...`.
- Python 3 with `requests` (`python -m pip install -r requirements-build.txt`).
- Internet access to fetch the Gradle distribution, Maven dependencies and pinned model assets during setup. The installed app itself does not require Internet access.

The repository includes Gradle Wrapper 8.9 and uses Android Gradle Plugin 8.5.2. Model weights, machine-local SDK paths, signing keys, test photographs and generated build artifacts are excluded from Git.

## Prepare assets

Run from the repository root:

```shell
python -m pip install -r requirements-build.txt
python tools/fetch_assets.py
python tools/fetch_segmentation.py
python tools/fetch_licenses.py
```

Downloads are verified against the checked-in SHA-256 manifests. The model assets are about 144 MB in decimal units; Gradle and Android dependencies require additional space. A failed download can be retried. A hash mismatch stops preparation rather than accepting different model bytes.

## Build

On Windows PowerShell:

```powershell
.\build.ps1
```

The script detects the SDK from `ANDROID_HOME` or `%LOCALAPPDATA%\Android\Sdk` and creates `local.properties` if absent. It builds a debug-signed preview and copies it to `artifacts/DepthSketch-0.3.3-android.apk`.

On macOS/Linux, set `ANDROID_HOME` or create `local.properties`, then:

```shell
chmod +x gradlew
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. You can also open the project in Android Studio after preparing the model assets.

Once all dependencies are cached, add `--offline` to Gradle, or use `.\build.ps1 -Offline`. Offline mode cannot populate a fresh dependency cache.

## Install

```shell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The public APK is an experimental debug-signed preview, not a Play Store production build. A local build normally uses your own debug key, so its signature may differ from the downloaded APK. Preserve any projects you need before changing installations. Signing keys are not part of this repository.

The CPU path is the compatibility baseline. Do not assume NNAPI, GPU inference or Huawei-specific acceleration is enabled.
