package cc.useforce.mc.ui;

/**
 * Box-drawing and block characters drawn as exact lines and blocks spanning the whole cell, as terminals do: font
 * glyphs for them never meet their neighbours (gaps in borders and rules).
 */
final class BoxDraw {
    private BoxDraw() {}

    // per character: up, right, down, left; 0 none, 1 light, 2 heavy, 3 double
    private static int[] arms(int cp) {
        return switch (cp) {
            case 0x2500, 0x2504, 0x2508, 0x254C -> new int[]{0, 1, 0, 1};       // ─ ┄ ┈ ╌
            case 0x2501, 0x2505, 0x2509, 0x254D -> new int[]{0, 2, 0, 2};       // ━
            case 0x2502, 0x2506, 0x250A, 0x254E -> new int[]{1, 0, 1, 0};       // │
            case 0x2503, 0x2507, 0x250B, 0x254F -> new int[]{2, 0, 2, 0};       // ┃
            case 0x250C, 0x256D -> new int[]{0, 1, 1, 0};                       // ┌ ╭
            case 0x2510, 0x256E -> new int[]{0, 0, 1, 1};                       // ┐ ╮
            case 0x2514, 0x2570, 0x23BF -> new int[]{1, 1, 0, 0};               // └ ╰ ⎿
            case 0x2518, 0x256F -> new int[]{1, 0, 0, 1};                       // ┘ ╯
            case 0x250F -> new int[]{0, 2, 2, 0};
            case 0x2513 -> new int[]{0, 0, 2, 2};
            case 0x2517 -> new int[]{2, 2, 0, 0};
            case 0x251B -> new int[]{2, 0, 0, 2};
            case 0x251C -> new int[]{1, 1, 1, 0};                               // ├
            case 0x2524 -> new int[]{1, 0, 1, 1};                               // ┤
            case 0x252C -> new int[]{0, 1, 1, 1};                               // ┬
            case 0x2534 -> new int[]{1, 1, 0, 1};                               // ┴
            case 0x253C -> new int[]{1, 1, 1, 1};                               // ┼
            case 0x2523 -> new int[]{2, 2, 2, 0};
            case 0x252B -> new int[]{2, 0, 2, 2};
            case 0x2533 -> new int[]{0, 2, 2, 2};
            case 0x253B -> new int[]{2, 2, 0, 2};
            case 0x254B -> new int[]{2, 2, 2, 2};
            case 0x2550 -> new int[]{0, 3, 0, 3};                               // ═
            case 0x2551 -> new int[]{3, 0, 3, 0};                               // ║
            case 0x2554 -> new int[]{0, 3, 3, 0};
            case 0x2557 -> new int[]{0, 0, 3, 3};
            case 0x255A -> new int[]{3, 3, 0, 0};
            case 0x255D -> new int[]{3, 0, 0, 3};
            case 0x2560 -> new int[]{3, 3, 3, 0};
            case 0x2563 -> new int[]{3, 0, 3, 3};
            case 0x2566 -> new int[]{0, 3, 3, 3};
            case 0x2569 -> new int[]{3, 3, 0, 3};
            case 0x256C -> new int[]{3, 3, 3, 3};
            case 0x2574 -> new int[]{0, 0, 0, 1};                               // ╴
            case 0x2575 -> new int[]{1, 0, 0, 0};                               // ╵
            case 0x2576 -> new int[]{0, 1, 0, 0};                               // ╶
            case 0x2577 -> new int[]{0, 0, 1, 0};                               // ╷
            case 0x2578 -> new int[]{0, 0, 0, 2};
            case 0x2579 -> new int[]{2, 0, 0, 0};
            case 0x257A -> new int[]{0, 2, 0, 0};
            case 0x257B -> new int[]{0, 0, 2, 0};
            default -> null;
        };
    }

    /** Draws `cp` in the cell at (x, y) if it is one of ours; false otherwise (the font draws it). */
    static boolean draw(Canvas c, int cp, int x, int y, int cw, int ch, int px, int color) {
        if (cp >= 0x2580 && cp <= 0x259F) return block(c, cp, x, y, cw, ch, color);
        int[] a = arms(cp);
        if (a == null) return false;
        int light = Math.max(1, Math.round(px / 14f)), heavy = light * 2;
        int cx = x + (cw - light) / 2, cy = y + (ch - light) / 2;
        for (int side = 0; side < 4; side++) {
            int k = a[side];
            if (k == 0) continue;
            int t = k == 2 ? heavy : light;
            int[] offs = k == 3 ? new int[]{-light, light} : new int[]{0};
            for (int o : offs) {
                int lx = cx - (t - light) / 2 + (side % 2 == 0 ? o : 0);
                int ly = cy - (t - light) / 2 + (side % 2 == 1 ? o : 0);
                switch (side) {
                    case 0 -> c.fill(lx, y, t, ly - y + t, color);              // up: to the centre
                    case 1 -> c.fill(lx, ly, x + cw - lx, t, color);            // right
                    case 2 -> c.fill(lx, ly, t, y + ch - ly, color);            // down
                    case 3 -> c.fill(x, ly, lx - x + t, t, color);              // left
                }
            }
        }
        return true;
    }

    private static boolean block(Canvas c, int cp, int x, int y, int cw, int ch, int color) {
        int a = color & 0xFF000000, rgb = color & 0xFFFFFF;
        switch (cp) {
            case 0x2588 -> c.fill(x, y, cw, ch, color);                          // █
            case 0x2580 -> c.fill(x, y, cw, ch / 2, color);                      // ▀
            case 0x2584 -> c.fill(x, y + ch / 2, cw, ch - ch / 2, color);        // ▄
            case 0x258C -> c.fill(x, y, cw / 2, ch, color);                      // ▌
            case 0x2590 -> c.fill(x + cw / 2, y, cw - cw / 2, ch, color);        // ▐
            case 0x2591 -> c.fill(x, y, cw, ch, (((a >>> 24) / 4) << 24) | rgb); // ░
            case 0x2592 -> c.fill(x, y, cw, ch, (((a >>> 24) / 2) << 24) | rgb); // ▒
            case 0x2593 -> c.fill(x, y, cw, ch, (((a >>> 24) * 3 / 4) << 24) | rgb); // ▓
            default -> {
                if (cp >= 0x2581 && cp <= 0x2587) {                              // ▁..▇: lower eighths
                    int hh = ch * (cp - 0x2580) / 8;
                    c.fill(x, y + ch - hh, cw, hh, color);
                } else if (cp >= 0x2589 && cp <= 0x258F) {                       // ▉..▏: left eighths
                    c.fill(x, y, cw * (0x2590 - cp) / 8, ch, color);
                } else return false;
            }
        }
        return true;
    }
}
