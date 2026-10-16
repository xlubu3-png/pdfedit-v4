package com.pdfedit.text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;

/**
 * Redraws an edited text run so it still looks like the original page: characters that did not
 * change are drawn again with the very same font object, glyph, size, colour and position, and new
 * characters take the font, size, colour, weight and spacing of their neighbours.
 *
 * <p>A new character is drawn, in order of preference, with (1) the source font's own glyph when the
 * page already draws that character in that font - this works even for subset fonts, because the
 * glyph code is reused rather than looked up through a character map; (2) the source font's full
 * program when it is embedded completely; (3) the closest font installed on this computer.
 *
 * <p>One instance serves one output document; it caches the fonts it had to embed.
 */
public final class RunPainter {

    /** Characters each font draws on a page, as unicode to glyph code - glyphs known to exist. */
    public static final class PageGlyphs {
        private final Map<COSDictionary, Map<String, Integer>> codes = new IdentityHashMap<>();

        public static PageGlyphs of(List<TextRun> runs) {
            PageGlyphs page = new PageGlyphs();
            for (TextRun run : runs) {
                for (Glyph glyph : run.glyphs()) {
                    page.codes.computeIfAbsent(glyph.font().getCOSObject(), k -> new HashMap<>())
                            .putIfAbsent(glyph.unicode(), glyph.code());
                }
            }
            return page;
        }

        /** Every character that any font used by {@code run} draws on this page, as one string. */
        public String charsOf(TextRun run) {
            Set<COSDictionary> fonts = Collections.newSetFromMap(new IdentityHashMap<>());
            StringBuilder chars = new StringBuilder();
            for (Glyph glyph : run.glyphs()) {
                COSDictionary font = glyph.font().getCOSObject();
                if (fonts.add(font)) {
                    codes.getOrDefault(font, Map.of()).keySet().forEach(chars::append);
                }
            }
            return chars.toString();
        }

        Integer codeFor(PDFont font, String unicode) {
            Map<String, Integer> forFont = codes.get(font.getCOSObject());
            return forFont == null ? null : forFont.get(unicode);
        }

        /**
         * A handful of the font's characters to look at when its look has to be judged: Hangul
         * syllables first, since their shapes show serif and weight best.
         */
        List<Map.Entry<String, Integer>> samples(PDFont font) {
            Map<String, Integer> forFont = codes.get(font.getCOSObject());
            if (forFont == null) {
                return List.of();
            }
            return forFont.entrySet().stream()
                    .filter(e -> !e.getKey().isBlank())
                    .sorted((a, b) -> {
                        boolean ha = isHangul(a.getKey());
                        boolean hb = isHangul(b.getKey());
                        return ha != hb ? (ha ? -1 : 1) : a.getKey().compareTo(b.getKey());
                    })
                    .limit(SAMPLE_CHARACTERS)
                    .map(e -> Map.entry(e.getKey(), e.getValue()))
                    .toList();
        }

        private static boolean isHangul(String s) {
            char c = s.charAt(0);
            return c >= 0xAC00 && c <= 0xD7A3;
        }
    }

    /** {@code scale} sizes a stand-in font's letters to the page's own (1 for the page's glyphs). */
    private record Placed(Glyph style, PDFont font, boolean sourceGlyph, int code, String text, float x, float scale) {
    }

    /** A font that draws a typed character, and how much to scale its letters to look as big as the page's. */
    private record Fallback(PDFont font, float scale) {
    }

    /** A line under letters {@code x0} to {@code x1} (user space), as thick as {@code size} says. */
    private record Underline(float x0, float x1, float size, PDColor color) {
    }

    /** Where every character of an edited run goes; drawn once the white-out has been laid down. */
    public static final class Layout {
        private final List<Placed> items;
        private final List<Underline> underlines;
        private final float rightEdge;

        private Layout(List<Placed> items, List<Underline> underlines, float rightEdge) {
            this.items = items;
            this.underlines = underlines;
            this.rightEdge = rightEdge;
        }

        /** Right edge of the redrawn run in user space. */
        public float rightEdge() {
            return rightEdge;
        }

        public void draw(PDPageContentStream cs, float baselineY) throws IOException {
            draw(cs, baselineY, 0f);
        }

        /** Draws the run {@code dx} points to the right of where it was laid out (negative: left). */
        public void draw(PDPageContentStream cs, float baselineY, float dx) throws IOException {
            cs.saveGraphicsState();
            try {
                for (Placed item : items) {
                    if (item.text().isBlank()) {
                        continue;
                    }
                    Glyph style = item.style();
                    cs.setNonStrokingColor(colorOrBlack(style.fill()));
                    cs.setStrokingColor(colorOrBlack(style.stroke()));
                    cs.setRenderingMode(RenderingMode.fromInt(style.renderMode()));
                    cs.setLineWidth(style.lineWidth());
                    // beginText()/endText() must pair up even when showText() throws mid-block, or
                    // the next beginText() fails with "Nested beginText() calls are not allowed".
                    cs.beginText();
                    try {
                        cs.setFont(item.font(), style.size() * item.scale());
                        cs.setHorizontalScaling(style.hScale() * 100f);
                        cs.newLineAtOffset(item.x() + dx, baselineY);
                        if (item.sourceGlyph()) {
                            cs.appendRawCommands(hexCode(item.font(), item.code()) + " Tj\n");
                        } else {
                            cs.showText(item.text());
                        }
                    } catch (IllegalArgumentException glyphError) {
                        // Character not supported by this font; the white-out still applied.
                    } finally {
                        cs.endText();
                    }
                }
                for (Underline line : underlines) {
                    cs.setStrokingColor(colorOrBlack(line.color()));
                    cs.setLineWidth(Math.max(0.3f, line.size() * 0.05f));
                    float y = baselineY - line.size() * 0.12f;
                    cs.moveTo(line.x0() + dx, y);
                    cs.lineTo(line.x1() + dx, y);
                    cs.stroke();
                }
            } finally {
                cs.restoreGraphicsState();
            }
        }
    }

    private static final int SAMPLE_CHARACTERS = 6;

    private final FontMatcher fontMatcher;
    private final Map<String, PDFont> substitutes = new HashMap<>();
    private final Map<COSDictionary, PDFont> reembedded = new IdentityHashMap<>();
    private final Map<COSDictionary, FontMatcher.Match> matches = new IdentityHashMap<>();
    private final Map<COSDictionary, Float> glyphScales = new IdentityHashMap<>();

    public RunPainter(FontMatcher fontMatcher) {
        this.fontMatcher = fontMatcher;
    }

    /**
     * Lays out {@code text} as the replacement of {@code original}, or returns empty when the
     * original characters can't be reproduced one by one (the caller then redraws the whole run
     * in a single substitute font).
     *
     * @param scope tells apart source documents sharing this painter: the same font name in two
     *              documents can be two different embedded fonts
     */
    public Optional<Layout> layout(PDDocument outDoc, TextRun original, String text, PageGlyphs page, String scope)
            throws IOException {
        return layout(outDoc, original, text, page, scope, List.of());
    }

    /**
     * Like {@link #layout(PDDocument, TextRun, String, PageGlyphs, String)}, with parts of the text in a weight,
     * colour, size or underline of their own ({@code segments}, which cover the text without gaps). The letters
     * keep the page's own glyphs whatever look they are given.
     */
    public Optional<Layout> layout(PDDocument outDoc, TextRun original, String text, PageGlyphs page, String scope,
            List<RunEdit.Segment> segments) throws IOException {
        if (!reproducible(original)) {
            return Optional.empty();
        }
        List<Glyph> glyphs = original.glyphs();
        String before = original.text();
        int n = glyphs.size();
        int m = text.length();

        // What stays the same at the start and at the end keeps its original glyphs.
        int prefix = 0;
        while (prefix < n && prefix < m && text.charAt(prefix) == before.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < n - prefix && suffix < m - prefix
                && text.charAt(m - 1 - suffix) == before.charAt(n - 1 - suffix)) {
            suffix++;
        }

        RunEdit.Segment[] looks = new RunEdit.Segment[m];
        for (RunEdit.Segment segment : segments) {
            for (int k = Math.max(0, segment.start()); k < Math.min(m, segment.end()); k++) {
                looks[k] = segment;
            }
        }

        float spacing = averageExtraSpacing(glyphs);
        float originalEnd = original.x() + original.width();
        List<Placed> placed = new ArrayList<>();
        List<Underline> underlines = new ArrayList<>();

        // New characters look like the character they replace, or like the one before an insertion.
        Glyph base = styleGlyph(glyphs, (n - prefix - suffix > 0 || prefix == 0) ? prefix : prefix - 1);
        float cursor = glyphs.get(0).x();
        // How far the page's glyphs have moved from where it drew them, and whether the glyph just before the
        // next one is itself an untouched original: then the next keeps the page's own spacing to it.
        float shift = 0f;
        boolean intact = true;
        int lastOriginal = -1;
        for (int k = 0; k < m; k++) {
            char c = text.charAt(k);
            String ch = String.valueOf(c);
            RunEdit.Segment look = looks[k];
            int gi = k < prefix ? k : (k >= m - suffix ? n - (m - k) : -1);
            float startX;
            Glyph style;
            if (gi >= 0) {
                Glyph g = glyphs.get(gi);
                if (intact && lastOriginal == gi - 1) {
                    startX = g.x() + shift;
                } else {
                    shift = cursor - g.x();
                    startX = cursor;
                }
                style = looked(g, look, original);
                float advance = (gi + 1 < n ? glyphs.get(gi + 1).x() : originalEnd) - g.x();
                float resize = style.size() / g.size();
                placed.add(new Placed(style, g.font(), true, g.code(), g.unicode(), startX, 1f));
                cursor = startX + advance * resize;
                intact = resize == 1f;
                lastOriginal = gi;
            } else {
                intact = false;
                startX = cursor;
                style = looked(base, look, original);
                if (Character.isWhitespace(c)) {
                    cursor += spaceAdvance(style, page) + spacing;
                } else {
                    Integer code = page.codeFor(base.font(), ch);
                    if (code != null) {
                        placed.add(new Placed(style, base.font(), true, code, ch, cursor, 1f));
                        cursor += advance(base.font(), code, style) + spacing;
                    } else {
                        Fallback fallback = fallbackFont(outDoc, base, ch, page, scope);
                        if (fallback == null) {
                            cursor += style.size() * 0.5f;
                        } else {
                            placed.add(new Placed(style, fallback.font(), false, -1, ch, cursor, fallback.scale()));
                            cursor += stringAdvance(fallback.font(), ch, style) * fallback.scale() + spacing;
                        }
                    }
                }
            }
            if (look != null && Boolean.TRUE.equals(look.underline())) {
                addUnderline(underlines, startX, cursor, style);
            }
        }
        return Optional.of(new Layout(placed, underlines, cursor));
    }

    /** One more underlined letter, joined to the line already running up to it when it has the same look. */
    private static void addUnderline(List<Underline> lines, float x0, float x1, Glyph style) {
        if (!lines.isEmpty()) {
            Underline last = lines.get(lines.size() - 1);
            if (Math.abs(last.x1() - x0) < 0.01f && last.size() == style.size() && sameColour(last.color(), style.fill())) {
                lines.set(lines.size() - 1, new Underline(last.x0(), x1, last.size(), last.color()));
                return;
            }
        }
        lines.add(new Underline(x0, x1, style.size(), style.fill()));
    }

    /**
     * {@code glyph} with the weight, colour and size a span asks for. Bold is the page's usual stand-in, a
     * thickened outline; taking bold away only works on such an outline, not on a bold font.
     */
    private static Glyph looked(Glyph glyph, RunEdit.Segment look, TextRun original) {
        if (look == null) {
            return glyph;
        }
        float size = glyph.size();
        if (look.size() != null && original.fontSize() > 0f) {
            size = glyph.size() * look.size() / original.fontSize();
        }
        int mode = glyph.renderMode();
        float lineWidth = glyph.lineWidth();
        PDColor fill = glyph.fill();
        PDColor stroke = glyph.stroke();
        java.awt.Color colour = look.color() == null ? null : RunEdit.colour(look.color(), null);
        if (colour != null) {
            fill = new PDColor(new float[] {colour.getRed() / 255f, colour.getGreen() / 255f, colour.getBlue() / 255f},
                    PDDeviceRGB.INSTANCE);
            stroke = fill;
        }
        if (Boolean.TRUE.equals(look.bold())) {
            if (mode != 2) {
                mode = 2;
                lineWidth = size * 0.03f;
                stroke = fill;
            }
        } else if (Boolean.FALSE.equals(look.bold()) && mode == 2) {
            mode = 0;
        }
        if (mode == 2 && look.size() != null && glyph.size() > 0f) {
            lineWidth = lineWidth * size / glyph.size();
        }
        return new Glyph(glyph.unicode(), glyph.font(), glyph.code(), glyph.x(), size, glyph.hScale(), mode,
                lineWidth, fill, stroke);
    }

    /**
     * The glyph whose look (font, size, colour, weight) new characters take: the one at {@code index},
     * unless that is a blank. A blank is often drawn in another font or colour than the letters around it -
     * a line's trailing space can even come from the next, blue, piece of text - and letters typed after
     * it must not turn blue or thin because of it; the nearest letter is used instead.
     */
    static Glyph styleGlyph(List<Glyph> glyphs, int index) {
        int n = glyphs.size();
        int at = Math.max(0, Math.min(n - 1, index));
        if (!glyphs.get(at).unicode().isBlank()) {
            return glyphs.get(at);
        }
        for (int distance = 1; distance < n; distance++) {
            int before = at - distance;
            if (before >= 0 && !glyphs.get(before).unicode().isBlank()) {
                return glyphs.get(before);
            }
            int after = at + distance;
            if (after < n && !glyphs.get(after).unicode().isBlank()) {
                return glyphs.get(after);
            }
        }
        return glyphs.get(at);
    }

    private static boolean sameColour(PDColor a, PDColor b) {
        return a == b || (a != null && b != null && a.getColorSpace() == b.getColorSpace()
                && java.util.Arrays.equals(a.getComponents(), b.getComponents()));
    }

    /**
     * Whether every character of the run can be drawn again from its glyph code: that needs a font
     * whose codes are plain 1-byte (simple fonts) or 2-byte (Identity CMap) values.
     */
    public static boolean canReuseGlyphs(TextRun run) {
        return reproducible(run);
    }

    private static boolean reproducible(TextRun run) {
        List<Glyph> glyphs = run.glyphs();
        if (glyphs.isEmpty()) {
            return false;
        }
        StringBuilder joined = new StringBuilder();
        for (Glyph glyph : glyphs) {
            if (glyph.unicode() == null || glyph.unicode().length() != 1 || glyph.code() < 0
                    || !codeIsPlain(glyph.font())) {
                return false;
            }
            joined.append(glyph.unicode());
        }
        return joined.toString().equals(run.text());
    }

    /** Simple fonts (Type 3 included) use 1-byte codes; composite fonts need an Identity CMap for 2-byte ones. */
    private static boolean codeIsPlain(PDFont font) {
        if (font instanceof PDType0Font type0) {
            return type0.getCMap() != null && type0.getCMap().getName() != null
                    && type0.getCMap().getName().startsWith("Identity");
        }
        return font instanceof PDSimpleFont;
    }

    private static String hexCode(PDFont font, int code) {
        return font instanceof PDType0Font ? String.format("<%04X>", code) : String.format("<%02X>", code);
    }

    private static PDColor colorOrBlack(PDColor color) {
        return color != null ? color : new PDColor(new float[] {0f}, PDDeviceGray.INSTANCE);
    }

    /**
     * Extra space the source put between characters on top of the glyph widths (character spacing,
     * "자간"), averaged over the run, so new characters get the same density.
     */
    private static float averageExtraSpacing(List<Glyph> glyphs) {
        float extra = 0f;
        int count = 0;
        for (int i = 0; i + 1 < glyphs.size(); i++) {
            Glyph glyph = glyphs.get(i);
            if (glyph.unicode().isBlank()) {
                continue;
            }
            float natural = advance(glyph.font(), glyph.code(), glyph);
            if (natural > 0f) {
                extra += (glyphs.get(i + 1).x() - glyph.x()) - natural;
                count++;
            }
        }
        if (count == 0) {
            return 0f;
        }
        float average = extra / count;
        float limit = glyphs.get(0).size() * 2f;
        return Math.max(-limit, Math.min(limit, average));
    }

    /**
     * Distance the glyph advances the pen. The font's displacement is in text-space units, so it is
     * right for every font kind - a Type 3 font's widths are scaled by its own font matrix, not 1/1000.
     */
    private static float advance(PDFont font, int code, Glyph style) {
        try {
            return font.getDisplacement(code).getX() * style.size() * style.hScale();
        } catch (IOException | RuntimeException e) {
            return 0f;
        }
    }

    /** Width of a typed space: the page's own space glyph in that font when it draws one. */
    private static float spaceAdvance(Glyph style, PageGlyphs page) {
        Integer code = page.codeFor(style.font(), " ");
        if (code != null) {
            float width = advance(style.font(), code, style);
            if (width > 0f) {
                return width;
            }
        }
        if (!(style.font() instanceof PDType3Font)) {
            try {
                float width = style.font().getSpaceWidth();
                if (width > 0f) {
                    return width / 1000f * style.size() * style.hScale();
                }
            } catch (RuntimeException ignored) {
                // fall through to the conventional quarter em
            }
        }
        return style.size() * 0.25f * style.hScale();
    }

    private static float stringAdvance(PDFont font, String text, Glyph style) {
        try {
            return font.getStringWidth(text) / 1000f * style.size() * style.hScale();
        } catch (IOException | RuntimeException e) {
            return style.size() * 0.5f;
        }
    }

    private static boolean canDraw(PDFont font, String text) {
        try {
            font.getStringWidth(text);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** A font that can draw {@code ch} in the spirit of the source font, or null if none can. */
    private Fallback fallbackFont(PDDocument outDoc, Glyph base, String ch, PageGlyphs page, String scope) {
        COSDictionary key = base.font().getCOSObject();
        if (!reembedded.containsKey(key)) {
            reembedded.put(key, FontMatcher.tryLoadOriginalEmbedded(outDoc, base.font()));
        }
        PDFont original = reembedded.get(key);
        if (original != null && canDraw(original, ch)) {
            return new Fallback(original, 1f);
        }
        // The installed font that looks most like the source font: by name for ordinary fonts, by
        // the look of their glyphs for fonts like Type 3 that carry no usable name.
        FontMatcher.Match match = matches.get(key);
        if (match == null) {
            match = fontMatcher.matchFor(base.font(), page);
            matches.put(key, match);
        }
        FontMatcher.Match chosen = match;
        PDFont substitute = substitutes.computeIfAbsent(
                scope + "|" + base.font().getName() + "|" + chosen.cssFamily + "|" + chosen.bold,
                k -> fontMatcher.loadFor(outDoc, chosen));
        if (!canDraw(substitute, ch)) {
            return null;
        }
        // The stand-in's letters may fill more of the em than the page's own: bring them to the page's size.
        float scale = glyphScales.computeIfAbsent(key, k -> fontMatcher.glyphSizeRatio(base.font(), page, chosen));
        return new Fallback(substitute, scale);
    }
}
