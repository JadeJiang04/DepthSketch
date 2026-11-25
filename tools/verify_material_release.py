"""Collect the prepared-image import release's completed device/build evidence."""
import hashlib
import json
import re
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
artifact = root / "artifacts/DepthSketch-0.3.3-android.apk"
checks = {}
for key, filename in {
    "direct_material_real_depth_alpha_history_project": "materials-0.3.3.txt",
    "freehand_editor": "instrumentation-0.3.3.txt",
    "interactive_segmentation_and_wallpaper": "additions-0.3.3.txt",
}.items():
    log = (root / "artifacts" / filename).read_text(encoding="utf-16")
    assert "INSTRUMENTATION_RESULT: result=PASS" in log, filename
    checks[key] = {"result": "PASS", "log": f"artifacts/{filename}"}
lint = (root / "app/build/reports/lint-results-debug.txt").read_text(encoding="utf-8")
match = re.search(r"(\d+) errors, (\d+) warnings", lint)
assert match and int(match[1]) == 0
signature = (root / "artifacts/signature-0.3.3.txt").read_text(encoding="utf-16")
assert "v2 scheme (APK Signature Scheme v2): true" in signature
with zipfile.ZipFile(artifact) as apk:
    abis = sorted({n.split("/")[1] for n in apk.namelist() if n.startswith("lib/") and n.endswith(".so")})
record = {
    "version": "0.3.3", "version_code": 6,
    "apk": str(artifact), "bytes": artifact.stat().st_size,
    "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
    "signature_v2": True, "abis": abis,
    "lint_errors": int(match[1]), "lint_warnings": int(match[2]),
    "checks": checks,
    "test_environment": "Dedicated Android x86_64 emulator; no editor-mutating tests on the user's phone.",
    "behavior": [
        "Prepared material preserves the decoded image canvas, exact alpha and disconnected components, with feather=0 by default.",
        "Opaque input remains a complete rectangle. Segmentation is skipped; real depth inference is still performed.",
        "Fully transparent input is rejected; the existing shared decoder limits the long edge to 1536 pixels.",
    ],
}
path = root / "artifacts/verification-0.3.3.json"
path.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"verification": str(path), "version": record["version"], "sha256": record["sha256"], "checks": checks}, ensure_ascii=False))
