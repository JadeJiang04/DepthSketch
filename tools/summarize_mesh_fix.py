"""Read real GPU exports; quantify clear-color gaps without modifying any image."""
import json
from pathlib import Path
import numpy as np
from PIL import Image

root = Path(__file__).resolve().parents[1]
folder = root / "testcase/output/scenes"
after_folder = folder / "mesh-fixed/scene-previews-0.3.2"
settings = json.loads((after_folder / "render-settings.json").read_text(encoding="utf-8"))
rows = []
for case in settings["cases"]:
    name = case["case"]
    old = np.asarray(Image.open(folder / "before-mesh-fix" / f"{name}.png").convert("RGB"))
    new = np.asarray(Image.open(after_folder / f"{name}.png").convert("RGB"))
    clear = np.array([17, 21, 18], dtype=np.uint8)
    old_mask = (old == clear).all(axis=2)
    new_mask = (new == clear).all(axis=2)
    rows.append({"case": name, "before_clear_color_pixels": int(old_mask.sum()),
                 "after_clear_color_pixels": int(new_mask.sum()),
                 "previous_clear_pixels_now_filled": int((old_mask & ~new_mask).sum()),
                 "photo_vertices": case["photo_vertices"], "photo_triangles": case["photo_triangles"],
                 "cached_draw_median_ms": case["cached_draw_median_ms"],
                 "cached_draw_p95_ms": case["cached_draw_p95_ms"],
                 "composite_reestimate_ms": case["composite_reestimate_ms"]})
report = {"version": "0.3.2", "cause": "Android-only depth-jump triangle deletion; original creator keeps a continuous 240-vertex long-edge grid.",
          "method": "Identical placements and depth settings; native Android GL exports at 960x1280. Clear-color counts are a diagnostic, not a ground-truth quality metric: natural photo pixels may share that RGB. Synthetic depth-step coverage and occlusion tests provide the independent check.",
          "checks": settings["mesh_checks"], "cases": rows,
          "limits": "Continuous 2.5D meshes can stretch at depth edges under tilt. Hidden background is not reconstructed. Re-estimation is a separate flattened scene, not guaranteed to improve all occlusion boundaries. Lighting is not implemented."}
(root / "artifacts/mesh-comparison-0.3.2.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
