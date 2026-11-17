package cc.useforce.mc.ui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Bitmap;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_GlyphSlot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.util.freetype.FreeType.*;

/**
 * The console's own text renderer: glyphs rasterised by FreeType at exactly the pixel size they are drawn, packed into
 * texture pages, and drawn 1:1 on screen pixels.
 * <p>
 * Not Minecraft's font system: that loads TTF fonts on several threads at once, and FreeType's TrueType hinting
 * interpreter is not safe for that (a crash in {@code TT_RunIns} when many sizes are defined). Here one FreeType
 * library of our own is used by the render thread only, with light auto-hinting, which never runs that interpreter.
 * JetBrains Mono (regular, bold) first, then Force's icons (a Nerd Fonts symbols subset), DejaVu Sans Mono and
 * DejaVu Sans for what is left. Emoji come in colour from the system's emoji font (the one a terminal uses: Noto
 * Color Emoji on Linux), else the bundled Noto Color Emoji, as a terminal draws them: those no text font has, those
 * marked with VS16 (⚠️), and the pictographs from U+1F000 on.
 */
public final class GlyphAtlas {
    private static final int PAGE = 1024;
    // FT_LOAD_TARGET_LIGHT (render mode LIGHT = 1, shifted into the load flags) and the auto-hinter: no bytecode
    private static final int FLAGS = FT_LOAD_RENDER | (1 << 16) | FT_LOAD_FORCE_AUTOHINT;
    private static final String[] REGULAR = {"jbm-regular.ttf", "nerd-symbols.ttf", "dejavu-sans-mono.ttf", "dejavu-sans.ttf"};
    private static final String[] BOLD = {"jbm-bold.ttf", "nerd-symbols.ttf", "dejavu-sans-mono.ttf", "dejavu-sans.ttf"};

    /** A glyph's place in a page and how it sits on the pen (x right, baseline). Empty when w == 0. `color`: its own
     * colours (an emoji), not tinted with the text's. */
    record Glyph(Page page, int u, int v, int w, int h, int left, int top, int advance, boolean color) {
        Glyph(Page page, int u, int v, int w, int h, int left, int top, int advance) {
            this(page, u, v, w, h, left, top, advance, false);
        }
    }

    /** Noto Color Emoji, in the mod: used when the system has no colour emoji font. */
    private static final String BUNDLED_EMOJI = "noto-color-emoji.ttf";

    /** Where systems keep their colour emoji font, after the one fontconfig names. */
    private static final String[] EMOJI_FONTS = {
            "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf",                  // Debian, Ubuntu
            "/usr/share/fonts/noto/NotoColorEmoji.ttf",                           // Arch
            "/usr/share/fonts/google-noto-color-emoji-fonts/NotoColorEmoji.ttf",  // Fedora
            "/usr/share/fonts/noto-emoji/NotoColorEmoji.ttf",
            "/System/Library/Fonts/Apple Color Emoji.ttc"};                      // macOS

    /** A size's cell: the advance of "M", the line box, and the baseline's place in it. */
    public record Metrics(int cellW, int cellH, int baseline) {}

    static final class Page {
        final Identifier id;
        final NativeImage image;
        final DynamicTexture texture;
        int x = 1, y = 1, rowH;
        boolean dirty;

        Page(int n) {
            id = Identifier.fromNamespaceAndPath("force_mc", "glyphs/" + n);
            image = new NativeImage(PAGE, PAGE, true);
            image.fillRect(0, 0, PAGE, PAGE, 0);
            texture = new DynamicTexture(() -> "force_mc glyphs " + n, image);
            Minecraft.getInstance().getTextureManager().register(id, texture);
        }
    }

    private static GlyphAtlas instance;
    private long library;
    private final List<FT_Face> regular = new ArrayList<>(), bold = new ArrayList<>();
    private final List<ByteBuffer> data = new ArrayList<>();      // the fonts' bytes: FreeType reads them in place
    private final List<Page> pages = new ArrayList<>();
    private final Map<Long, Glyph> glyphs = new HashMap<>();
    private final Map<Integer, Metrics> metrics = new HashMap<>();
    private boolean failed;
    private FT_Face emoji;
    private boolean emojiLooked;

    /** The atlas (render thread only). */
    public static GlyphAtlas get() {
        if (instance == null) instance = new GlyphAtlas();
        return instance;
    }

    private GlyphAtlas() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            if (FT_Init_FreeType(p) != 0) throw new IllegalStateException("FT_Init_FreeType");
            library = p.get(0);
            for (String f : REGULAR) regular.add(face(f, stack));
            for (String f : BOLD) bold.add(face(f, stack));
        } catch (RuntimeException | IOException e) {
            failed = true;
            System.err.println("force_mc: no text renderer: " + e);
        }
    }

    private FT_Face face(String file, MemoryStack stack) throws IOException {
        byte[] bytes;
        try (InputStream in = GlyphAtlas.class.getResourceAsStream("/assets/force_mc/fonts/" + file)) {
            if (in == null) throw new IOException("missing font " + file);
            bytes = in.readAllBytes();
        }
        return face(bytes, file, stack);
    }

    private FT_Face face(byte[] bytes, String file, MemoryStack stack) throws IOException {
        ByteBuffer buf = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        data.add(buf);
        PointerBuffer f = stack.mallocPointer(1);
        if (FT_New_Memory_Face(library, buf, 0, f) != 0) throw new IOException("FT_New_Memory_Face " + file);
        return FT_Face.create(f.get(0));
    }

    public boolean ok() { return !failed; }

    /** The cell for `px`: "M"'s advance wide, a 1.3 line tall, the baseline where the ink of a line is centred. */
    public Metrics metrics(int px) {
        return metrics.computeIfAbsent(px, k -> {
            if (failed) return new Metrics(Math.max(1, px * 6 / 10), Math.round(px * 1.3f), px);
            FT_Face f = regular.get(0);
            FT_Set_Pixel_Sizes(f, 0, px);
            int asc = (int) (f.size().metrics().ascender() >> 6), desc = (int) (-f.size().metrics().descender() >> 6);
            int cellH = Math.round(px * 1.3f);
            int cellW = Math.max(1, glyph('M', px, false).advance());
            // the ink of a line runs about 0.78 em above the baseline to 0.22 em below: centre that in the cell
            int baseline = Math.round(cellH / 2f + 0.28f * px);
            if (baseline > cellH) baseline = Math.min(cellH, asc + (cellH - asc - desc) / 2);
            return new Metrics(cellW, cellH, baseline);
        });
    }

    Glyph glyph(int cp, int px, boolean isBold) {
        return glyph(cp, px, isBold, false);
    }

    Glyph glyph(int cp, int px, boolean isBold, boolean asEmoji) {
        long key = ((long) px << 34) | (asEmoji ? 1L << 33 : 0) | (isBold ? 1L << 32 : 0) | (cp & 0xFFFFFFFFL);
        Glyph g = glyphs.get(key);
        if (g == null) {
            g = render(cp, px, isBold, asEmoji);
            glyphs.put(key, g);
        }
        return g;
    }

    private Glyph render(int cp, int px, boolean isBold, boolean asEmoji) {
        if (failed) return new Glyph(null, 0, 0, 0, 0, 0, 0, Math.max(1, px * 6 / 10));
        if (asEmoji || (cp >= 0x1F000 && cp <= 0x1FAFF)) {
            Glyph e = renderEmoji(cp, px);
            if (e != null) return e;
        }
        List<FT_Face> faces = isBold ? bold : regular;
        FT_Face face = null;
        for (FT_Face f : faces) {
            if (FT_Get_Char_Index(f, cp) != 0) {
                face = f;
                break;
            }
        }
        if (face == null) {                            // no text font has it: an emoji (✅ ❌), or the missing box
            Glyph e = renderEmoji(cp, px);
            if (e != null) return e;
            face = faces.get(0);
        }
        FT_Set_Pixel_Sizes(face, 0, px);
        if (FT_Load_Char(face, cp, FLAGS) != 0) return new Glyph(null, 0, 0, 0, 0, 0, 0, Math.max(1, px * 6 / 10));
        FT_GlyphSlot slot = face.glyph();
        FT_Bitmap bm = slot.bitmap();
        int w = bm.width(), h = bm.rows(), advance = (int) Math.round(slot.advance().x() / 64.0);
        if (w == 0 || h == 0) return new Glyph(null, 0, 0, 0, 0, 0, 0, advance);
        Page page = place(w, h);
        int u = page.x, v = page.y;
        page.x += w + 1;
        ByteBuffer buf = bm.buffer(Math.abs(bm.pitch()) * h);
        int pitch = bm.pitch();
        boolean mono = bm.pixel_mode() == FT_PIXEL_MODE_MONO;
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int a = mono ? ((buf.get(row * pitch + (col >> 3)) >> (7 - (col & 7))) & 1) * 255
                        : buf.get(row * pitch + col) & 0xFF;
                page.image.setPixel(u + col, v + row, (a << 24) | 0xFFFFFF);
            }
        }
        page.dirty = true;
        return new Glyph(page, u, v, w, h, slot.bitmap_left(), slot.bitmap_top(), advance);
    }

    /** The system's colour emoji font, found once: the one fontconfig picks for "emoji", else a known place. */
    private FT_Face emojiFace() {
        if (emojiLooked) return emoji;
        emojiLooked = true;
        List<String> paths = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("fc-match", "-f", "%{file}", "emoji:color=true").redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor() == 0 && !out.isEmpty()) paths.add(out);
        } catch (IOException | RuntimeException e) {
            // no fontconfig: the known places
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        paths.addAll(List.of(EMOJI_FONTS));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (String path : paths) {
                java.nio.file.Path file = java.nio.file.Path.of(path);
                if (!java.nio.file.Files.isRegularFile(file)) continue;
                try {
                    FT_Face f = face(java.nio.file.Files.readAllBytes(file), path, stack);
                    if (FT_HAS_COLOR(f)) {
                        emoji = f;
                        return f;
                    }
                    FT_Done_Face(f);                       // a plain font (fontconfig's last resort): not an emoji one
                } catch (IOException | RuntimeException ignored) {
                }
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {        // none on the system: the bundled one
            FT_Face f = face(BUNDLED_EMOJI, stack);
            if (FT_HAS_COLOR(f)) {
                emoji = f;
                return f;
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("force_mc: no colour emoji font: emoji are drawn as boxes (" + e + ")");
        }
        return null;
    }

    /**
     * An emoji in colour, about two cells wide and as tall as the text, centred on the line: a bitmap font's strike
     * (Noto, Apple) scaled down, or a colour outline font rendered at the size.
     */
    private Glyph renderEmoji(int cp, int px) {
        FT_Face f = emojiFace();
        if (f == null) return null;
        int index = FT_Get_Char_Index(f, cp);
        if (index == 0) return null;
        Metrics m = metrics(px);
        int targetH = Math.round(px * 1.15f);
        if (f.num_fixed_sizes() > 0) {
            int best = 0;
            var sizes = f.available_sizes();
            for (int i = 1; i < f.num_fixed_sizes(); i++) if (sizes.get(i).height() > sizes.get(best).height()) best = i;
            if (FT_Select_Size(f, best) != 0) return null;
        } else FT_Set_Pixel_Sizes(f, 0, targetH);
        if (FT_Load_Glyph(f, index, FT_LOAD_COLOR | FT_LOAD_RENDER) != 0) return null;
        FT_Bitmap bm = f.glyph().bitmap();
        int bw = bm.width(), bh = bm.rows();
        if (bw == 0 || bh == 0 || bm.pixel_mode() != FT_PIXEL_MODE_BGRA) return null;
        int th = targetH, tw = Math.round((float) bw * th / bh);
        if (tw > 2 * m.cellW()) {
            tw = 2 * m.cellW();
            th = Math.max(1, Math.round((float) bh * tw / bw));
        }
        Page page = place(tw, th);
        int u = page.x, v = page.y;
        page.x += tw + 1;
        ByteBuffer buf = bm.buffer(Math.abs(bm.pitch()) * bh);
        int pitch = Math.abs(bm.pitch());
        // area average of the premultiplied source, then straight alpha
        for (int y = 0; y < th; y++) {
            int y0 = y * bh / th, y1 = Math.max(y0 + 1, (y + 1) * bh / th);
            for (int x = 0; x < tw; x++) {
                int x0 = x * bw / tw, x1 = Math.max(x0 + 1, (x + 1) * bw / tw);
                long sb = 0, sg = 0, sr = 0, sa = 0;
                for (int yy = y0; yy < y1; yy++) {
                    int row = yy * pitch;
                    for (int xx = x0; xx < x1; xx++) {
                        int o = row + xx * 4;
                        sb += buf.get(o) & 0xFF;
                        sg += buf.get(o + 1) & 0xFF;
                        sr += buf.get(o + 2) & 0xFF;
                        sa += buf.get(o + 3) & 0xFF;
                    }
                }
                int argb = 0;
                if (sa > 0) {
                    int n = (y1 - y0) * (x1 - x0);
                    int a = (int) (sa / n);
                    int r = (int) Math.min(255, sr * 255 / sa), g = (int) Math.min(255, sg * 255 / sa),
                            b = (int) Math.min(255, sb * 255 / sa);
                    argb = (a << 24) | (r << 16) | (g << 8) | b;
                }
                page.image.setPixel(u + x, v + y, argb);
            }
        }
        page.dirty = true;
        int top = m.baseline() - (m.cellH() - th) / 2;      // centred in the line box
        return new Glyph(page, u, v, tw, th, 0, top, tw, true);
    }

    private Page place(int w, int h) {
        Page p = pages.isEmpty() ? null : pages.get(pages.size() - 1);
        if (p != null && p.x + w + 1 > PAGE) {
            p.x = 1;
            p.y += p.rowH + 1;
            p.rowH = 0;
        }
        if (p == null || p.y + h + 1 > PAGE) {
            p = new Page(pages.size());
            pages.add(p);
        }
        p.rowH = Math.max(p.rowH, h);
        return p;
    }

    /** Uploads the pages new glyphs went into; call before drawing them. */
    private void flush() {
        for (Page p : pages) {
            if (p.dirty) {
                p.texture.upload();
                p.dirty = false;
            }
        }
    }

    /**
     * Draws `text` with the pen at (x, line top + baseline); in `cellW` > 0 every character takes one cell (the
     * terminal), else its own advance. Returns the pen's x after it.
     */
    public int draw(Canvas c, String text, int x, int lineTop, int px, boolean isBold, int color, int cellW) {
        Metrics m = metrics(px);
        int baseline = lineTop + m.baseline();
        List<Glyph> line = glyphs(text, px, isBold);
        flush();
        int pen = x;
        for (Glyph g : line) {
            if (g.w() > 0) {
                // a glyph wider than its cell (a fallback font's) is centred in it rather than pushing the grid; an
                // emoji in one cell (⚠️) runs on into the next, as in a terminal
                int dx = cellW > 0 && g.advance() != cellW ? (cellW - g.advance()) / 2 : 0;
                if (g.color()) dx = Math.max(0, dx);
                int tint = g.color() ? (color & 0xFF000000) | 0xFFFFFF : color;
                c.g.blit(RenderPipelines.GUI_TEXTURED, g.page().id, pen + dx + g.left(), baseline - g.top(), g.u(),
                        g.v(), g.w(), g.h(), PAGE, PAGE, tint);
            }
            pen += cellW > 0 ? cellW : g.advance();
        }
        return pen;
    }

    public int width(String text, int px, boolean isBold) {
        int w = 0;
        for (Glyph g : glyphs(text, px, isBold)) w += g.advance();
        return w;
    }

    /** The glyphs of `text`: VS16 makes the character before it an emoji and takes no place itself. */
    private List<Glyph> glyphs(String text, int px, boolean isBold) {
        List<Glyph> line = new ArrayList<>(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xFE0F) continue;
            boolean asEmoji = i < text.length() && text.charAt(i) == '\uFE0F';
            line.add(glyph(cp, px, isBold, asEmoji));
        }
        return line;
    }
}
