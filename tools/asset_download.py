"""Download model assets without changing the checked-in provenance manifest."""
import hashlib
from pathlib import Path

import requests


def download_verified(folder: Path, record: dict) -> None:
    target = folder / record["file"]
    folder.mkdir(parents=True, exist_ok=True)
    candidate = target
    if not target.exists():
        print("Downloading", target.name, flush=True)
        candidate = target.with_suffix(target.suffix + ".part")
        with requests.get(record["url"], stream=True, timeout=(30, 120)) as response:
            response.raise_for_status()
            with candidate.open("wb") as output:
                for chunk in response.iter_content(1024 * 1024):
                    output.write(chunk)
    digest = hashlib.sha256(candidate.read_bytes()).hexdigest()
    if candidate.stat().st_size != record["bytes"] or digest != record["sha256"]:
        raise ValueError(f"Asset checksum mismatch: {candidate}. Remove the invalid file and retry.")
    if candidate != target:
        candidate.replace(target)
    print("Verified", target.name, record["bytes"], digest, flush=True)
