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

    /** Whether anything was learned since the results were last saved. */
    private static final java.util.concurrent.atomic.AtomicBoolean CHANGED = new java.util.concurrent.atomic.AtomicBoolean();

    private static final int SAVED_MAGIC = 0x54434831; // "TCH1"

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
            CHANGED.set(true);
            return Optional.of(winner);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Ink sizes (width, height in pixels at {@link #SIZE}) of characters in installed fonts, by font file and character. */
    private static final Map<String, int[]> INK_CACHE = new ConcurrentHashMap<>();

    /** Closer than this to 1 the sizes are called equal: no resizing for a hair. */
    private static final double SAME_SIZE = 0.04;

    /**
     * How large the letters of {@code face} come out next to the page's own letters at the same nominal size,
     * as the factor that brings them to the page's size (below 1: the installed font is the bigger one). A PDF
     * font - a Type 3 font above all - often fills less of its em than an installed font does, so letters typed
     * in the stand-in would stand out as too big. Judged on the ink of the characters both draw; 1 when that
     * cannot be told.
     */
    float sizeRatio(PDFont source, List<Map.Entry<String, Integer>> samples, SystemFonts.Face face) {
        try {
            List<Double> ratios = new ArrayList<>();
            for (Map.Entry<String, Integer> sample : samples) {
                int[] mine = inkSize(renderSourceGlyph(source, sample.getValue()));
                int[] theirs = standInInk(face, sample.getKey());
                if (mine != null && theirs != null) {
                    ratios.add(Math.sqrt((double) mine[0] * mine[1] / ((double) theirs[0] * theirs[1])));
                }
            }
            if (ratios.isEmpty()) {
                return 1f;
            }
            ratios.sort(Double::compare);
            double median = ratios.get(ratios.size() / 2);
            return Math.abs(median - 1) < SAME_SIZE ? 1f : (float) Math.min(1.1, Math.max(0.7, median));
        } catch (IOException | RuntimeException e) {
            return 1f;
        }
    }

    private static int[] standInInk(SystemFonts.Face face, String text) throws IOException {
        String key = face.file() + "|" + face.collectionFontName() + "|" + text;
        int[] cached = INK_CACHE.get(key);
        if (cached != null) {
            return cached.length == 0 ? null : cached;
        }
        int[] size = null;
        try (PDDocument doc = new PDDocument()) {
            PDFont font = face.embed(doc);
            PDPage page = new PDPage(new PDRectangle(PAGE, PAGE));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                try {
                    cs.setFont(font, SIZE);
                    cs.newLineAtOffset(14, 22);
                    cs.showText(text);
                } catch (IllegalArgumentException missingGlyph) {
                    // this font has no such character: nothing to compare
                } finally {
                    cs.endText();
                }
            }
            size = inkSize(new PDFRenderer(doc).renderImage(0, 1f));
        }
        INK_CACHE.put(key, size == null ? new int[0] : size);
        CHANGED.set(true);
        return size;
    }

    /** Width and height in pixels of the dark area of {@code image}; null when nothing is drawn. */
    private static int[] inkSize(BufferedImage image) {
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
        return maxX < 0 ? null : new int[] {maxX - minX + 1, maxY - minY + 1};
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
                CHANGED.set(true);
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

    /**
     * Saves what was learned about fonts - which installed font each page font looks like, and the installed
     * fonts' letters drawn for the comparison - so that after a restart a document opens without drawing them
     * all again. Nothing is written when nothing new was learned.
     *
     * @param fonts what the installed fonts are (their files, sizes and dates): the results hold only for them
     * @return whether the file was written
     */
    static boolean save(java.nio.file.Path file, String fonts) throws IOException {
        if (!CHANGED.getAndSet(false)) {
            return false;
        }
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(temp))))) {
            out.writeInt(SAVED_MAGIC);
            out.writeUTF(fonts);
            Map<Integer, FontMatcher.Match> matches = Map.copyOf(CACHE);
            out.writeInt(matches.size());
            for (Map.Entry<Integer, FontMatcher.Match> e : matches.entrySet()) {
                out.writeInt(e.getKey());
                out.writeUTF(e.getValue().cssFamily);
                out.writeBoolean(e.getValue().bold);
                out.writeDouble(e.getValue().similarity);
            }
            Map<String, boolean[]> glyphs = Map.copyOf(GLYPH_CACHE);
            out.writeInt(glyphs.size());
            for (Map.Entry<String, boolean[]> e : glyphs.entrySet()) {
                out.writeUTF(e.getKey());
                out.writeInt(e.getValue().length);
                java.util.BitSet bits = new java.util.BitSet(e.getValue().length);
                for (int i = 0; i < e.getValue().length; i++) {
                    bits.set(i, e.getValue()[i]);
                }
                byte[] packed = bits.toByteArray();
                out.writeInt(packed.length);
                out.write(packed);
            }
            Map<String, int[]> inks = Map.copyOf(INK_CACHE);
            out.writeInt(inks.size());
            for (Map.Entry<String, int[]> e : inks.entrySet()) {
                out.writeUTF(e.getKey());
                out.writeInt(e.getValue().length);
                for (int v : e.getValue()) {
                    out.writeInt(v);
                }
            }
        } catch (IOException | RuntimeException e) {
            CHANGED.set(true);
            java.nio.file.Files.deleteIfExists(temp);
            throw e;
        }
        java.nio.file.Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    /**
     * Reads back what {@link #save} wrote, when it was learned about the same installed fonts; a missing, damaged
     * or out-of-date file is left alone (it is written anew once something is learned).
     *
     * @return how many results were read
     */
    static int load(java.nio.file.Path file, String fonts) {
        if (!java.nio.file.Files.isRegularFile(file)) {
            return 0;
        }
        Map<Integer, FontMatcher.Match> matches = new java.util.HashMap<>();
        Map<String, boolean[]> glyphs = new java.util.HashMap<>();
        Map<String, int[]> inks = new java.util.HashMap<>();
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(
                new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(file))))) {
            if (in.readInt() != SAVED_MAGIC || !in.readUTF().equals(fonts)) {
                return 0;
            }
            for (int n = in.readInt(); n > 0; n--) {
                int key = in.readInt();
                matches.put(key, new FontMatcher.Match(in.readUTF(), in.readBoolean(), in.readDouble()));
            }
            for (int n = in.readInt(); n > 0; n--) {
                String key = in.readUTF();
                int length = in.readInt();
                byte[] packed = new byte[in.readInt()];
                in.readFully(packed);
                java.util.BitSet bits = java.util.BitSet.valueOf(packed);
                boolean[] mask = new boolean[length];
                for (int i = 0; i < length; i++) {
                    mask[i] = bits.get(i);
                }
                glyphs.put(key, mask);
            }
            for (int n = in.readInt(); n > 0; n--) {
                String key = in.readUTF();
                int[] size = new int[in.readInt()];
                for (int i = 0; i < size.length; i++) {
                    size[i] = in.readInt();
                }
                inks.put(key, size);
            }
        } catch (IOException | RuntimeException damaged) {
            return 0;
        }
        CACHE.putAll(matches);
        GLYPH_CACHE.putAll(glyphs);
        INK_CACHE.putAll(inks);
        return matches.size() + glyphs.size() + inks.size();
    }

    /** Forgets everything learned (for tests). */
    static void forget() {
        CACHE.clear();
        GLYPH_CACHE.clear();
        INK_CACHE.clear();
        CHANGED.set(false);
    }

    /** How many results are known (for tests). */
    static int known() {
        return CACHE.size() + GLYPH_CACHE.size() + INK_CACHE.size();
    }
}
