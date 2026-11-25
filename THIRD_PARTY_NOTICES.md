# Third-party components

The MIT license at the repository root covers the original DepthSketch application code. Third-party models, exports, libraries and assets retain their own licenses and notices.

| Component | Use | Upstream / license |
| --- | --- | --- |
| Depth Anything V2 **Small** | Relative depth estimation | [Official project](https://github.com/DepthAnything/Depth-Anything-V2), Apache-2.0 for Small |
| DA V2 Small ONNX export | Android inference weights | [onnx-community export](https://huggingface.co/onnx-community/depth-anything-v2-small); pinned revision and SHA-256 in `app/src/main/assets/provenance.json` |
| MobileSAM | Point-prompted object segmentation | [Official project](https://github.com/ChaoningZhang/MobileSAM), Apache-2.0 |
| MobileSAM ONNX exports | Image encoder and prompt decoder | [Acly/MobileSAM](https://huggingface.co/Acly/MobileSAM), MIT export notice in addition to upstream MobileSAM terms; pinned revision and SHA-256 in `app/src/main/assets/segmentation-provenance.json` |
| ONNX Runtime Android 1.27.0 | Local model execution | [Microsoft ONNX Runtime](https://github.com/microsoft/onnxruntime), MIT and bundled third-party notices |
| Gradle Wrapper | Build bootstrap | [Gradle](https://github.com/gradle/gradle), Apache-2.0 |
| Example image `sample.jpg` | Built-in demonstration | Distributed in the [Depth Anything V2 repository](https://github.com/DepthAnything/Depth-Anything-V2/tree/main/assets/examples); original provenance is recorded with the model manifest |

Full model/runtime notices are included in [`app/src/main/assets/licenses/`](app/src/main/assets/licenses/) and bundled in the APK. The exported image-encoder script is retained there as provenance, not used to run the application.

Only DA V2 **Small** is used. This project's license does not grant rights to other DA model sizes or to images supplied by users. Local test photographs and user artwork are excluded from this public repository.
