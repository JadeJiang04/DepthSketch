# v0.3.3 — Editable 3D wallpaper preview

[Download the APK](https://github.com/JadeJiang04/DepthSketch/releases/tag/v0.3.3) · [中文说明](../../zh-CN/README.md)

An offline Android scene editor built around Depth Anything V2 Small, MobileSAM and native OpenGL rendering.

- Turn a background photo into a depth-aware 2.5D scene.
- Extract objects from other images with point prompts, then collage them with matching depth.
- Import prepared image materials directly, preserving transparent edges and disconnected details.
- Draw independent 3D strokes and edit object depth, scale, relief and XYZ rotation.
- Arrange foreground/background occlusion and publish a tilt-responsive live wallpaper.
- Export PNGs or editable `.dsketch` projects. Optionally refine exported images with an external generative model.

**New in this version:** direct prepared-image import, alongside the existing point-selection workflow. Transparent images retain alpha; opaque images remain full rectangles. No segmentation runs for the new import path, but depth estimation still does.

**Requirements:** Android 8.0+, OpenGL ES 2.0. APK includes ARM64, ARMv7 and x86_64 libraries and all inference models; about 216 MiB. The current UI is Chinese. No API key, account or network connection is needed for the core workflow.

This is a debug-signed experimental preview, not a Play Store release. Single-image depth is relative 2.5D: large rotations can stretch surfaces or expose missing background. Model preparation speed and segmentation quality vary by phone and image. External generative post-processing is optional and is not integrated into the app.

Verification records in this folder cover prepared image import, existing drawing/editing, interactive segmentation and Android live wallpaper integration on a dedicated emulator. The phone installation was checked separately. The APK checksum is included in `verification.json` and the release's `SHA256SUMS.txt`.

## 中文

这是一个基于端侧深度估计与分割的可编辑 3D 壁纸体验版：图片估深、点选抠图拼贴、透明素材直导、3D 绘画、前后遮挡和重力视差壁纸，无需 API 密钥或云端推理。

本版新增“⋯ → 直接导入图片素材”，保留原有点选抠图。准备好的透明素材保留透明度和分离细节，不透明图片整张加入；跳过分割，仍估计深度。

Android 8.0 及以上，当前中文界面，模型随 APK 提供。导出的 PNG 可以再交给外部大模型精修；这不是当前应用内置的 API 功能。
