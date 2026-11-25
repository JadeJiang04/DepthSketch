param([switch]$Offline)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$androidSdkPath = $env:ANDROID_HOME
if (-not $androidSdkPath) { $androidSdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path -LiteralPath $androidSdkPath)) { throw 'Install Android SDK or set ANDROID_HOME.' }
if (-not (Test-Path -LiteralPath 'local.properties')) {
    Set-Content -LiteralPath 'local.properties' -Value ('sdk.dir=' + $androidSdkPath.Replace('\','/')) -Encoding ASCII
}
if (-not (Test-Path -LiteralPath 'app/src/main/assets/depth_anything_v2_small.onnx')) {
    python tools/fetch_assets.py
    if ($LASTEXITCODE -ne 0) { throw 'Model download failed.' }
}
if (-not (Test-Path -LiteralPath 'app/src/main/assets/mobile_sam_encoder.onnx') -or -not (Test-Path -LiteralPath 'app/src/main/assets/mobile_sam_decoder.onnx')) {
    python tools/fetch_segmentation.py
    if ($LASTEXITCODE -ne 0) { throw 'Segmentation model download failed.' }
}
$gradleArgs = @(':app:assembleDebug', '--console=plain')
if ($Offline) { $gradleArgs += '--offline' }
& .\gradlew.bat @gradleArgs
if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
New-Item -ItemType Directory -Path 'artifacts' -Force | Out-Null
Copy-Item -LiteralPath 'app/build/outputs/apk/debug/app-debug.apk' -Destination 'artifacts/DepthSketch-0.3.3-android.apk' -Force
Get-FileHash -LiteralPath 'artifacts/DepthSketch-0.3.3-android.apk' -Algorithm SHA256
