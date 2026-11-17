#!/usr/bin/env python3
"""Draws the mod's icon: Force's bolt as a 16×16 Minecraft item (a dark tile with a bevel, the bolt in Force's coral),
scaled to 512×512 without smoothing. Writes src/client/resources/assets/force_mc/icon.png and .github/assets/icon.png.

    python3 scripts/icon.py          # needs Pillow
"""
import random
from pathlib import Path

from PIL import Image

CORAL, LIGHT, SHADE = (217, 119, 87), (240, 163, 130), (168, 80, 56)
BOLT = [                     # centred: its mass sits at (7.1, 7.5) of the tile's (7.5, 7.5)
    "................",
    "................",
    ".........###....",
    "........###.....",
    ".......###......",
    "......###.......",
    ".....###........",
    "....#########...",
    "...#########....",
    ".......###......",
    "......###.......",
    ".....###........",
    "....###.........",
    "...##...........",
    "..#.............",
    "................",
]


def icon() -> Image.Image:
    g = len(BOLT)
    random.seed(1)
    im = Image.new("RGBA", (g, g))
    p = im.load()
    for y in range(g):
        for x in range(g):
            v = 36 + random.randint(-5, 5)                  # the tile: dark, a little grain
            p[x, y] = (v, v, v, 255)
    for i in range(g):                                      # bevel: light top and left, dark bottom and right
        p[i, 0] = p[0, i] = (66, 66, 66, 255)
        p[i, g - 1] = p[g - 1, i] = (18, 18, 18, 255)
    on = lambda x, y: 0 <= x < g and 0 <= y < g and BOLT[y][x] == "#"
    for y in range(g):
        for x in range(g):
            if on(x, y):                                    # lit from above, shaded on the lower right
                c = LIGHT if not on(x, y - 1) else SHADE if not on(x, y + 1) and not on(x + 1, y) else CORAL
                p[x, y] = c + (255,)
    return im.resize((512, 512), Image.NEAREST)


if __name__ == "__main__":
    root = Path(__file__).resolve().parent.parent
    im = icon()
    for out in (root / "src/client/resources/assets/force_mc/icon.png", root / ".github/assets/icon.png"):
        out.parent.mkdir(parents=True, exist_ok=True)
        im.save(out, optimize=True)
        print(out.relative_to(root))
