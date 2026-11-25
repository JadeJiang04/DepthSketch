"""Summarize paired real-device diagnostics; use app-private I/O runs only."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
folder = root / "artifacts" / "latency"
before = json.loads((folder / "baseline-phone.json").read_text(encoding="utf-8"))
after = json.loads((folder / "optimized-phone.json").read_text(encoding="utf-8"))
assert before["device"] == after["device"]
assert before["io_location"] == after["io_location"] == "app_private_cache"
paired = []
for old, new in zip(before["cases"], after["cases"]):
    assert old["source"] == new["source"]
    row = {"source": old["source"], "stages": {}}
    for key in ("transfer_write_ms", "transfer_read_ms", "wallpaper_write_ms",
                "wallpaper_repeat_write_ms", "wallpaper_read_ms"):
        row["stages"][key] = {
            "before_ms": old[key], "after_ms": new[key],
            "speedup": round(old[key] / max(1, new[key]), 2),
        }
    row["crop_before"] = [old["crop_width"], old["crop_height"]]
    row["crop_after"] = [new["crop_width"], new["crop_height"]]
    row["foreground_pixels_removed"] = old["mask"]["foreground"] - new["mask"]["foreground"]
    assert old["mask"]["largest_components"][0] == new["mask"]["largest_components"][0]
    row["largest_component_preserved"] = True
    paired.append(row)
summary = {
    "device": after["device"], "android": after["android"],
    "method": "Single paired run per supplied character; real models and identical prompts. "
              "App-private file I/O, device held awake in both runs; previous stay-awake setting restored. "
              "First wallpaper write includes background PNG encoding; repeat reuses its cache.",
    "cases": paired, "picker_activity": after["picker_activity"],
    "existing_user_project": after["existing_user_project"],
    "cleanup_regression": after["cleanup_regression"],
    "note": "Handoff times are serialization only. The separately measured picker confirmation "
            "includes depth inference, crop and serialization. "
            "Point refinement is about 0.20-0.27 s after adding cleanup (previously about 0.10 s). "
            "No network or cloud inference; no active wallpaper changes in real-device diagnostics.",
}
(folder / "comparison.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
print(json.dumps(summary, indent=2, ensure_ascii=False))
