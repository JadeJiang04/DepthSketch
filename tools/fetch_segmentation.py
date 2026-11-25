"""Fetch pinned MobileSAM encoder/decoder; notices are tracked in assets/licenses."""
import json
from pathlib import Path

from asset_download import download_verified

assets = Path(__file__).resolve().parents[1] / "app/src/main/assets"
for record in json.loads((assets / "segmentation-provenance.json").read_text(encoding="utf-8")):
    download_verified(assets, record)
