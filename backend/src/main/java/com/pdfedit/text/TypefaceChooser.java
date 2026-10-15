package com.pdfedit.text;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Tells what a font whose name says nothing looks like - Type 3 fonts are called "T1", "T2" and
 * their glyphs are drawing programs - by drawing a few of its characters and comparing them with
 * the same characters in the installed fonts: serif or sans, regular or bold, whichever matches best.
 */
final class TypefaceChooser {

    private static final int PAGE = 80;
    private static final float SIZE = 48f;
    private static final int GRID = 24;
    private static final int DARK = 128;

    /** Results by what the sample glyphs look like, so the comparison runs once per font, not per request. */
    private static final Map<Integer, FontMatcher.Match> CACHE = new ConcurrentHashMap<>();

    /** Bitmaps of characters in installed fonts, by font file and character. */
    private static final Map<String, boolean[]> GLYPH_CACHE = new ConcurrentHashMap<>();

    private final FontMatcher fontMatcher;

    TypefaceChooser(FontMatcher fontMatcher) {
        this.fontMatcher = fontMatcher;
    }

    /** @param samples characters the font draws, as unicode to glyph code */
    FontMatcher.Match choose(PDFont font, List<Map.Entry<String, Integer>> samples) {
        return judge(font, samples).orElseGet(() -> fontMatcher.match(false, false));
    }

    /** Like {@link #choose}, but empty when the glyphs could not be compared at all. */
    Optional<FontMatcher.Match> judge(PDFont font, List<Map.Entry<String, Integer>> samples) {
        if (samples.isEmpty()) {
            return Optional.empty();
        }
        try {
            List<boolean[]> reference = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            for (Map.Entry<String, Integer> sample : samples) {
                boolean[] mask = mask(renderSourceGlyph(font, sample.getValue()));
                if (ink(mask) > 0) {
                    reference.add(mask);
                    texts.add(sample.getKey());
                }
            }
            if (reference.isEmpty()) {
                return Optional.empty();
            }
            int key = Arrays.deepHashCode(reference.toArray());
            FontMatcher.Match cached = CACHE.get(key);
            if (cached != null) {
                return Optional.of(cached);
            }

            // The two stock families first: the answer when nothing installed does clearly better.
            FontMatcher.Match best = null;
            double bestScore = -1;
            for (boolean serif : new boolean[] {false, true}) {
                for (boolean bold : new boolean[] {false, true}) {
                    FontMatcher.Match candidate = fontMatcher.match(serif, bold);
                    Optional<SystemFonts.Face> face = fontMatcher.faceFor(candidate);
                    if (face.isEmpty()) {
                        continue;
                    }
                    double score = similarity(reference, installedGlyphMasks(face.get(), texts));
                    if (score > bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
            if (best == null) {
                return Optional.empty();
            }

            // Then every installed Korean font: a Type 3 font (HWP output) says nothing about what it is,
            // and the real one - HY견고딕, 함초롬돋움 - is often installed and fits far better.
            FontMatcher.Match winner = new FontMatcher.Match(best.cssFamily, best.bold, bestScore);
            double winnerScore = bestScore;
            for (SystemFonts.Face face : fontMatcher.koreanFaces()) {
                try {
                    double score = similarity(reference, installedGlyphMasks(face, texts));
                    if (score > winnerScore && beats(score, bestScore)) {
                        winnerScore = score;
                        winner = new FontMatcher.Match(face.family(), face.bold(), score);
                    }
                } catch (IOException | RuntimeException unusable) {
                    // a font that cannot be read is simply not a candidate
                }
            }
            CACHE.put(key, winner);
            return Optional.of(winner);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** How much better than the stock choice another font has to be before it replaces it: no churn for a hair. */
    private static final double MARGIN = 0.03;

    static boolean beats(double score, double stockScore) {
        return score >= stockScore + MARGIN;
    }

    /** Average overlap, 0 to 1, between the page's glyphs and a candidate's. */
    private static double similarity(List<boolean[]> reference, List<boolean[]> candidate) {
        double score = 0;
        for (int i = 0; i < reference.size(); i++) {
            score += dice(reference.get(i), candidate.get(i));
        }
        return score / reference.size();
    }

    private static BufferedImage renderSourceGlyph(PDFont font, int code) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(PAGE, PAGE));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, SIZE);
                cs.newLineAtOffset(14, 22);
                cs.appendRawCommands(String.format(font instanceof PDType0Font ? "<%04X> Tj\n" : "<%02X> Tj\n", code));
                cs.endText();
            }
            return new PDFRenderer(doc).renderImage(0, 1f);
        }
    }

    /**
     * The characters drawn in an installed font, as bitmaps. The font file is read and embedded once
     * for all of them - on a slow disk (a network or container mount) reading a 10 MB collection per
     * character would take minutes - and the bitmaps are remembered for the next font that needs them.
     */
    private static List<boolean[]> installedGlyphMasks(SystemFonts.Face face, List<String> texts) throws IOException {
        String prefix = face.file() + "|" + face.collectionFontName() + "|";
        List<String> missing = texts.stream().filter(t -> !GLYPH_CACHE.containsKey(prefix + t)).toList();
        if (!missing.isEmpty()) {
            try (PDDocument doc = new PDDocument()) {
                PDFont font = face.embed(doc);
                for (String text : missing) {
                    PDPage page = new PDPage(new PDRectangle(PAGE, PAGE));
                    doc.addPage(page);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        try {
                            cs.setFont(font, SIZE);
                            cs.newLineAtOffset(14, 22);
                            cs.showText(text);
                        } catch (IllegalArgumentException missingGlyph) {
                            // this font has no such character: the page stays blank and scores zero
                        } finally {
                            cs.endText();
                        }
                    }
                }
                PDFRenderer renderer = new PDFRenderer(doc);
                for (int i = 0; i < missing.size(); i++) {
                    GLYPH_CACHE.put(prefix + missing.get(i), mask(renderer.renderImage(i, 1f)));
                }
            }
        }
        List<boolean[]> masks = new ArrayList<>();
        for (String text : texts) {
            masks.add(GLYPH_CACHE.get(prefix + text));
        }
        return masks;
    }

    /** The glyph's ink as a GRID x GRID bitmap of its bounding box, so size and position drop out. */
    private static boolean[] mask(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (isDark(image.getRGB(x, y))) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        boolean[] mask = new boolean[GRID * GRID];
        if (maxX < 0) {
            return mask;
        }
        int w = maxX - minX + 1;
        int h = maxY - minY + 1;
        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                int x = minX + gx * w / GRID;
                int y = minY + gy * h / GRID;
                mask[gy * GRID + gx] = isDark(image.getRGB(Math.min(x, maxX), Math.min(y, maxY)));
            }
        }
        return mask;
    }

    private static boolean isDark(int rgb) {
        int gray = (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF)) / 3;
        return gray < DARK;
    }

    private static int ink(boolean[] mask) {
        int count = 0;
        for (boolean on : mask) {
            if (on) {
                count++;
            }
        }
        return count;
    }

    /** Overlap of two bitmaps from 0 to 1; a bolder or thinner stroke than the reference scores lower. */
    private static double dice(boolean[] a, boolean[] b) {
        int overlap = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] && b[i]) {
                overlap++;
            }
        }
        int total = ink(a) + ink(b);
        return total == 0 ? 0 : 2.0 * overlap / total;
    }
}
