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
 * DejaVu Sans for what is left.
 */
public final class GlyphAtlas {
    private static final int PAGE = 1024;
    // FT_LOAD_TARGET_LIGHT (render mode LIGHT = 1, shifted into the load flags) and the auto-hinter: no bytecode
    private static final int FLAGS = FT_LOAD_RENDER | (1 << 16) | FT_LOAD_FORCE_AUTOHINT;
    private static final String[] REGULAR = {"jbm-regular.ttf", "nerd-symbols.ttf", "dejavu-sans-mono.ttf", "dejavu-sans.ttf"};
    private static final String[] BOLD = {"jbm-bold.ttf", "nerd-symbols.ttf", "dejavu-sans-mono.ttf", "dejavu-sans.ttf"};

    /** A glyph's place in a page and how it sits on the pen (x right, baseline). Empty when w == 0. */
    record Glyph(Page page, int u, int v, int w, int h, int left, int top, int advance) {}

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
        long key = ((long) px << 33) | (isBold ? 1L << 32 : 0) | (cp & 0xFFFFFFFFL);
        Glyph g = glyphs.get(key);
        if (g == null) {
            g = render(cp, px, isBold);
            glyphs.put(key, g);
        }
        return g;
    }

    private Glyph render(int cp, int px, boolean isBold) {
        if (failed) return new Glyph(null, 0, 0, 0, 0, 0, 0, Math.max(1, px * 6 / 10));
        List<FT_Face> faces = isBold ? bold : regular;
        FT_Face face = faces.get(0);
        for (FT_Face f : faces) {
            if (FT_Get_Char_Index(f, cp) != 0) {
                face = f;
                break;
            }
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
        List<Glyph> line = new ArrayList<>(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            line.add(glyph(cp, px, isBold));
        }
        flush();
        int pen = x;
        for (Glyph g : line) {
            if (g.w() > 0) {
                // a glyph wider than its cell (a fallback font's) is centred in it rather than pushing the grid
                int dx = cellW > 0 && g.advance() != cellW ? (cellW - g.advance()) / 2 : 0;
                c.g.blit(RenderPipelines.GUI_TEXTURED, g.page().id, pen + dx + g.left(), baseline - g.top(), g.u(),
                        g.v(), g.w(), g.h(), PAGE, PAGE, color);
            }
            pen += cellW > 0 ? cellW : g.advance();
        }
        return pen;
    }

    public int width(String text, int px, boolean isBold) {
        int w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            w += glyph(cp, px, isBold).advance();
        }
        return w;
    }
}
