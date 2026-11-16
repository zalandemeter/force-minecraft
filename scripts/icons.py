#!/usr/bin/env python3
"""Rebuilds assets/force_mc/fonts/nerd-symbols.ttf: the Nerd Fonts symbols Force draws, taken from a Force checkout
(escapes and raw characters in its source, plus ranges it computes), cut from Symbols Nerd Font Mono.

    python3 scripts/icons.py ~/force /path/to/SymbolsNerdFontMono-Regular.ttf     # needs fonttools (pyftsubset)
"""
import re
import subprocess
import sys
from pathlib import Path

# glyphs Force computes rather than writes (keep in step with Force's source)
COMPUTED = [range(0xF0A9E, 0xF0A9E + 8)]        # the working pie, md circle-slice 1..8 (sidebar model.rs glyph())

force, font = Path(sys.argv[1]), sys.argv[2]
private = lambda cp: 0xE000 <= cp <= 0xF8FF or 0xF0000 <= cp <= 0x10FFFD
cps = set()
for path in list(force.glob("crates/**/*.rs")) + list(force.glob("force/*.py")) + list(force.glob("share/**/*")):
    if not path.is_file():
        continue
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        continue
    for m in re.finditer(r"\\u\{([0-9A-Fa-f]{4,6})\}|\\U([0-9A-Fa-f]{8})|\\u([0-9A-Fa-f]{4})", text):
        cps.add(int(next(g for g in m.groups() if g), 16))
    cps.update(ord(ch) for ch in text if private(ord(ch)))
for r in COMPUTED:
    cps.update(r)
cps = sorted(cp for cp in cps if private(cp))
out = Path(__file__).resolve().parent.parent / "src/client/resources/assets/force_mc/fonts/nerd-symbols.ttf"
subprocess.run(["pyftsubset", font, "--unicodes=" + ",".join(f"U+{cp:04X}" for cp in cps), f"--output-file={out}"],
               check=True)
print(f"{len(cps)} icons -> {out}")
