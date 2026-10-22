#!/usr/bin/env python3
"""Export the custom AISight detector and Ultralytics YOLO26n depth model for Android."""
from __future__ import annotations

import shutil
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WEIGHTS = ROOT / "models" / "aisight.pt"
ASSETS = ROOT / "app" / "src" / "main" / "assets"
EXPECTED_LABELS = [
    "stop_sign", "e-tricycle", "car", "person", "e-bike", "e-trike", "tricycle",
    "van", "bench", "bike", "bus", "cat", "crosswalk", "dog", "manhole",
    "hazard_sign", "jeepney", "motorcycle", "door", "pit_hole", "puddle", "ramps",
    "stairs", "tree", "truck", "uncovered_manhole", "utility_pole",
]


def tflite_candidates(stem: str, started: float, returned: object) -> list[Path]:
    found: list[Path] = []
    if returned:
        p = Path(str(returned))
        if p.is_file() and p.suffix.lower() == ".tflite":
            found.append(p)
        elif p.is_dir():
            found.extend(p.rglob("*.tflite"))
    for p in ROOT.rglob("*.tflite"):
        try:
            if p not in found and p.stat().st_mtime >= started - 1 and stem.lower() in p.name.lower():
                found.append(p)
        except OSError:
            continue
    # Format version changes may put the filename in a nested SavedModel dir, so
    # search recently generated outputs under the expected model's export folder.
    for p in ROOT.rglob("*.tflite"):
        try:
            if p not in found and p.stat().st_mtime >= started - 1 and stem.lower() in str(p.parent).lower():
                found.append(p)
        except OSError:
            continue
    found = [p for p in found if p.is_file() and p.stat().st_size > 100_000]
    found.sort(key=lambda p: ("float32" not in str(p).lower(), -p.stat().st_mtime, p.stat().st_size))
    return found


def export_one(YOLO, model_path: str, stem: str, destination: Path, *, is_custom: bool) -> None:
    started = time.time()
    print(f"\n=== Loading {model_path} ===", flush=True)
    model = YOLO(model_path)
    if is_custom:
        raw_names = getattr(model, "names", {})
        if isinstance(raw_names, dict):
            names = [raw_names[k] for k in sorted(raw_names, key=lambda item: int(item))]
        else:
            names = list(raw_names)
        print(f"Custom detector classes ({len(names)}): {names}", flush=True)
        if names != EXPECTED_LABELS:
            raise RuntimeError("Uploaded class names/order don't match Android decoder.\n"
                               f"Expected: {EXPECTED_LABELS}\nFound: {names}")

    print(f"Exporting {stem} as float32 LiteRT/TFLite at 640px. This may take several minutes.", flush=True)
    # `nms=None` retains raw detector predictions for Android-side NMS.
    # Depth is dense regression output and does not use NMS.
    kwargs = dict(format="litert", imgsz=640, batch=1)
    if is_custom:
        kwargs["nms"] = None
    returned = model.export(**kwargs)
    candidates = tflite_candidates(stem, started, returned)
    if not candidates:
        raise RuntimeError(f"{stem}: export reported {returned!r}, but no corresponding .tflite file was found.")
    source = candidates[0]
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    print(f"Wrote {destination.relative_to(ROOT)} ({destination.stat().st_size:,} bytes) from {source}", flush=True)


def main() -> int:
    if not WEIGHTS.is_file():
        print(f"ERROR: custom weights not found: {WEIGHTS}", file=sys.stderr)
        return 2
    try:
        from ultralytics import YOLO
    except Exception as exc:
        print("ERROR: export dependencies missing. Install scripts/requirements-export.txt", file=sys.stderr)
        print(exc, file=sys.stderr)
        return 3
    ASSETS.mkdir(parents=True, exist_ok=True)
    try:
        export_one(YOLO, str(WEIGHTS), "aisight", ASSETS / "aisight.tflite", is_custom=True)
        # Ultralytics fetches the official checkpoint if it is not already cached.
        export_one(YOLO, "yolo26n-depth.pt", "yolo26n-depth", ASSETS / "depth.tflite", is_custom=False)
    except Exception as exc:
        print(f"\nERROR: model export failed: {exc}", file=sys.stderr)
        raise
    print("Both Android model assets exported successfully.", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
