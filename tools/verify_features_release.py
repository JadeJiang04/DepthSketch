"""Collect the completed 0.3.2 build/device evidence into one release record."""
import hashlib
import json
import re
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
artifact = root / "artifacts/DepthSketch-0.3.2-android.apk"
logs = {}
for key, name in {
    "freehand": "instrumentation-0.3.2.txt",
    "wallpaper_system": "additions-0.3.2.txt",
    "pan_inspector_feather_reestimate": "interactions-0.3.2.txt",
    "real_phone_mesh_and_composite_exports": "mesh-fix-phone.txt",
}.items():
    text = (root / "artifacts" / name).read_text(encoding="utf-16")
    assert "INSTRUMENTATION_RESULT: result=PASS" in text, name
    logs[key] = {"result": "PASS", "log": f"artifacts/{name}"}
lint_text = (root / "app/build/reports/lint-results-debug.txt").read_text(encoding="utf-8")
match = re.search(r"(\d+) errors, (\d+) warnings", lint_text)
assert match and int(match[1]) == 0
signature = (root / "artifacts/signature-0.3.2.txt").read_text(encoding="utf-16")
assert "v2 scheme (APK Signature Scheme v2): true" in signature
with zipfile.ZipFile(artifact) as apk:
    abis = sorted({name.split("/")[1] for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")})
comparison = json.loads((root / "artifacts/mesh-comparison-0.3.2.json").read_text(encoding="utf-8"))
record = {
    "version": "0.3.2", "version_code": 5, "apk": str(artifact),
    "bytes": artifact.stat().st_size, "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
    "signature_v2": True, "abis": abis, "lint_errors": int(match[1]), "lint_warnings": int(match[2]),
    "checks": logs, "mesh_gpu_checks": comparison["checks"],
    "outputs": "testcase/output/scenes/index.html", "ui_outputs": "testcase/output/ui-0.3.2/",
    "lighting_plan": "docs/lighting-plan.md",
    "boundaries": [
        "Continuous 2.5D photo surfaces may stretch at depth boundaries under rotation; hidden background is not inferred.",
        "Composite re-estimation is an independent flattened experimental result, not a guaranteed quality improvement.",
        "Lighting is a documented next-stage plan; this release retains unlit image colors and adds adjustable alpha-edge feathering.",
        "Native wallpaper application checks run on a dedicated emulator; real-phone exports do not replace the user's wallpaper or editor scene.",
    ],
}
(root / "artifacts/verification-0.3.2.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(record, ensure_ascii=False, indent=2))
