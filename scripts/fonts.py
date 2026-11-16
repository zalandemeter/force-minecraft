#!/usr/bin/env python3
"""Writes the font definitions (assets/force_mc/font/px_<N>[_bold].json): one per pixel size, rasterised at exactly
that size (oversample 1), so the console draws glyphs 1:1 on screen pixels. JetBrains Mono first, then DejaVu Sans
Mono and DejaVu Sans for what it lacks (box drawing, braille, symbols). Run after changing SIZES."""
import json
from pathlib import Path

SIZES = [12, 13, 14, 15, 16, 17, 18, 20, 22, 24, 26, 28, 32, 36, 40, 44, 48]
out = Path(__file__).resolve().parent.parent / "src/client/resources/assets/force_mc/font"
for f in out.glob("px_*.json"):
    f.unlink()
for px in SIZES:
    for bold in (False, True):
        main = "force_mc:jbm-bold.ttf" if bold else "force_mc:jbm-regular.ttf"
        providers = [{"type": "ttf", "file": f, "size": float(px), "oversample": 1.0, "shift": [0.0, 0.0]}
                     for f in (main, "force_mc:dejavu-sans-mono.ttf", "force_mc:dejavu-sans.ttf")]
        name = f"px_{px}{'_bold' if bold else ''}.json"
        (out / name).write_text(json.dumps({"providers": providers}, indent="\t") + "\n")
print(f"{len(SIZES) * 2} fonts in {out}")
