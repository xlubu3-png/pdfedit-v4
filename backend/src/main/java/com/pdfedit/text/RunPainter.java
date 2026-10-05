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

    private record Placed(Glyph style, PDFont font, boolean sourceGlyph, int code, String text, float x) {
    }

    /** Where every character of an edited run goes; drawn once the white-out has been laid down. */
    public static final class Layout {
        private final List<Placed> items;
        private final float rightEdge;

        private Layout(List<Placed> items, float rightEdge) {
            this.items = items;
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
                        cs.setFont(item.font(), style.size());
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
        if (!reproducible(original)) {
            return Optional.empty();
        }
        List<Glyph> glyphs = original.glyphs();
        String before = original.text();
        int n = glyphs.size();
        int m = text.length();

        // What stays the same at the start and at the end keeps its original glyphs untouched.
        int prefix = 0;
        while (prefix < n && prefix < m && text.charAt(prefix) == before.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < n - prefix && suffix < m - prefix
                && text.charAt(m - 1 - suffix) == before.charAt(n - 1 - suffix)) {
            suffix++;
        }

        float spacing = averageExtraSpacing(glyphs);
        float originalEnd = original.x() + original.width();
        List<Placed> placed = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            placed.add(retained(glyphs.get(i), glyphs.get(i).x()));
        }

        // New characters look like the character they replace, or like the one before an insertion.
        Glyph base = (n - prefix - suffix > 0 || prefix == 0) ? glyphs.get(prefix) : glyphs.get(prefix - 1);
        float cursor = prefix < n ? glyphs.get(prefix).x() : originalEnd;
        for (int k = prefix; k < m - suffix; k++) {
            char c = text.charAt(k);
            String ch = String.valueOf(c);
            if (Character.isWhitespace(c)) {
                cursor += spaceAdvance(base, page) + spacing;
                continue;
            }
            Integer code = page.codeFor(base.font(), ch);
            if (code != null) {
                placed.add(new Placed(base, base.font(), true, code, ch, cursor));
                cursor += advance(base.font(), code, base) + spacing;
                continue;
            }
            PDFont font = fallbackFont(outDoc, base, ch, page, scope);
            if (font == null) {
                cursor += base.size() * 0.5f;
                continue;
            }
            placed.add(new Placed(base, font, false, -1, ch, cursor));
            cursor += stringAdvance(font, ch, base) + spacing;
        }

        // The rest of the line moves along with the edit, keeping its own spacing.
        float delta = cursor - (suffix > 0 ? glyphs.get(n - suffix).x() : originalEnd);
        for (int i = n - suffix; i < n; i++) {
            placed.add(retained(glyphs.get(i), glyphs.get(i).x() + delta));
        }
        float rightEdge = suffix > 0 ? originalEnd + delta : cursor;
        return Optional.of(new Layout(placed, Math.max(rightEdge, cursor)));
    }

    private static Placed retained(Glyph glyph, float x) {
        return new Placed(glyph, glyph.font(), true, glyph.code(), glyph.unicode(), x);
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
    private PDFont fallbackFont(PDDocument outDoc, Glyph base, String ch, PageGlyphs page, String scope) {
        COSDictionary key = base.font().getCOSObject();
        if (!reembedded.containsKey(key)) {
            reembedded.put(key, FontMatcher.tryLoadOriginalEmbedded(outDoc, base.font()));
        }
        PDFont original = reembedded.get(key);
        if (original != null && canDraw(original, ch)) {
            return original;
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
        return canDraw(substitute, ch) ? substitute : null;
    }
}
