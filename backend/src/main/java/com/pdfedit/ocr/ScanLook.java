package com.pdfedit.ocr;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.pdfedit.text.SystemFonts;

/**
 * How a scanned line looks, so the text box rewriting it can look the same: the installed font that draws its
 * words most like the scan, at the size and place that puts them over the scanned ones, with the letter spacing
 * that makes them as long - and the spaces between its words, which text recognition sometimes misses.
 *
 * <p>Fonts are compared by drawing the line's text in each and laying it over the scan's ink: the one whose
 * strokes fall where the scan's do wins.
 */
final class ScanLook {

    /** The text is first drawn this many pixels high to measure it. */
    private static final float PROBE_SIZE = 100f;
    private static final int PROBE_MARGIN = 10;
    /**
     * Between two words the stretch without ink is this much (a share of the letters' height) wider than between
     * the letters of a word, and at least {@link #WORD_GAP} wide.
     */
    private static final double WORD_GAP_MORE = 0.22;
    private static final double WORD_GAP = 0.3;
    /** Letter spacing kept within these shares of the font size: more says the font is wrong, not spaced. */
    private static final double MIN_SPACING = -0.12;
    private static final double MAX_SPACING = 0.6;

    /** Patches of ink this small, on their own, are specks of the scan, not parts of letters. */
    private static final int SPECK = 4;

    private static final Map<String, Optional<Font>> AWT_FONTS = new ConcurrentHashMap<>();

    private ScanLook() {
    }

    /** The scanned letters' solid ink (rows, then columns) and the rectangle around it (ends included). */
    record Ink(boolean[][] solid, int left, int top, int right, int bottom) {

        /**
         * The ink without its specks (a compressed scan strews dots of a pixel or two around its letters, which
         * would make them look taller and wider than they are); null when there is no ink at all.
         */
        static Ink of(boolean[][] scanned) {
            boolean[][] solid = withoutSpecks(scanned);
            int left = Integer.MAX_VALUE;
            int top = Integer.MAX_VALUE;
            int right = -1;
            int bottom = -1;
            for (int y = 0; y < solid.length; y++) {
                for (int x = 0; x < solid[y].length; x++) {
                    if (solid[y][x]) {
                        left = Math.min(left, x);
                        top = Math.min(top, y);
                        right = Math.max(right, x);
                        bottom = Math.max(bottom, y);
                    }
                }
            }
            return right < 0 ? null : new Ink(solid, left, top, right, bottom);
        }

        int width() {
            return right - left + 1;
        }

        int height() {
            return bottom - top + 1;
        }

        /** The mask with every patch of ink no bigger than {@link #SPECK} pixels (touching corners count) left out. */
        static boolean[][] withoutSpecks(boolean[][] mask) {
            int h = mask.length;
            int w = h == 0 ? 0 : mask[0].length;
            boolean[][] out = new boolean[h][];
            for (int y = 0; y < h; y++) {
                out[y] = mask[y].clone();
            }
            boolean[][] seen = new boolean[h][w];
            int[] stack = new int[w * h];
            List<Integer> patch = new ArrayList<>();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (!mask[y][x] || seen[y][x]) {
                        continue;
                    }
                    patch.clear();
                    int top = 0;
                    stack[top++] = y * w + x;
                    seen[y][x] = true;
                    while (top > 0) {
                        int at = stack[--top];
                        patch.add(at);
                        int py = at / w;
                        int px = at % w;
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                int ny = py + dy;
                                int nx = px + dx;
                                if (ny >= 0 && ny < h && nx >= 0 && nx < w && mask[ny][nx] && !seen[ny][nx]) {
                                    seen[ny][nx] = true;
                                    stack[top++] = ny * w + nx;
                                }
                            }
                        }
                    }
                    if (patch.size() <= SPECK) {
                        for (int at : patch) {
                            out[at / w][at % w] = false;
                        }
                    }
                }
            }
            return out;
        }
    }

    /**
     * How the box draws the line, in pixels of the line's picture: the font, its size, where the first baseline
     * starts and the spacing added after every letter; {@code likeness} 0 to 1; {@code coverage} how much of the
     * rectangle around its letters the font's ink covers (0 to 1, soft edges counting in part).
     */
    record Look(SystemFonts.Face face, double sizePx, double originX, double baselineY, double spacingPx,
            double likeness, double coverage) {
    }

    /**
     * The text spaced as the scan is: between two letters a space where the stretch without ink between them is
     * clearly wider than between the letters of a word, none where it is not. Recognition reads the spaces off
     * those gaps too, but more coarsely, and a font set wide gets spaces inside its words.
     *
     * @param centers where each letter was seen along the line (0 to 1), as recognition gave it
     * @param width   the line's width in pixels of {@code ink}'s picture
     */
    static String spacedAsScanned(String text, List<Float> centers, Ink ink, int width) {
        if (centers == null || centers.size() != text.length()) {
            return text;
        }
        List<Character> letters = new ArrayList<>();
        List<Double> places = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != ' ') {
                letters.add(text.charAt(i));
                places.add(centers.get(i) * (double) width);
            }
        }
        if (letters.size() < 3) {
            return text;
        }
        boolean[] empty = new boolean[width];
        for (int x = 0; x < width; x++) {
            empty[x] = true;
            for (int y = ink.top(); y <= ink.bottom() && empty[x]; y++) {
                empty[x] = !ink.solid()[y][x];
            }
        }
        // The widest stretch without ink between each two letters next to each other.
        double[] gaps = new double[letters.size() - 1];
        for (int i = 0; i < gaps.length; i++) {
            int from = (int) Math.max(0, Math.min(places.get(i), places.get(i + 1)));
            int to = (int) Math.min(width - 1, Math.max(places.get(i), places.get(i + 1)));
            int run = 0;
            for (int x = from; x <= to; x++) {
                run = empty[x] ? run + 1 : 0;
                gaps[i] = Math.max(gaps[i], run);
            }
        }
        double[] sorted = gaps.clone();
        java.util.Arrays.sort(sorted);
        double usual = sorted[sorted.length / 2];
        double space = Math.max(usual + WORD_GAP_MORE * ink.height(), WORD_GAP * ink.height());
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < letters.size(); i++) {
            out.append(letters.get(i));
            if (i < gaps.length && gaps[i] >= space) {
                out.append(' ');
            }
        }
        return out.toString();
    }

    /** How each face that can draw {@code text} draws it over the scan: sized, placed and spaced, and how alike. */
    static Map<SystemFonts.Face, Look> looks(String text, Ink ink, List<SystemFonts.Face> faces) {
        Map<SystemFonts.Face, Look> out = new java.util.LinkedHashMap<>();
        for (SystemFonts.Face face : faces) {
            Font font = awtFont(face).orElse(null);
            if (font != null && font.canDisplayUpTo(text) == -1) {
                Look look = look(font, face, text, ink);
                if (look != null) {
                    out.put(face, look);
                }
            }
        }
        return out;
    }

    /** The face's look at {@code sizePx}, spaced to the scan's length; null when it cannot draw the text. */
    static Look at(String text, Ink ink, SystemFonts.Face face, double sizePx) {
        Font font = awtFont(face).orElse(null);
        if (font == null || font.canDisplayUpTo(text) != -1) {
            return null;
        }
        Drawn probe = Drawn.of(font.deriveFont(PROBE_SIZE), text, 0);
        return probe == null ? null : fitted(font, face, text, ink, probe, sizePx / PROBE_SIZE, true);
    }

    /** Tells faces apart: the file and, in a collection, the font's name in it. */
    static String key(SystemFonts.Face face) {
        return face.file() + "|" + face.collectionFontName();
    }

    /**
     * {@code text} in {@code font}, sized and spaced to cover the scan's ink, and how alike the two then are. The
     * size is tried as the letters' height asks (spacing making up the length) and as the line's length asks (the
     * font's own spacing): on a coarse scan the height is a few pixels off, the length much less.
     */
    private static Look look(Font font, SystemFonts.Face face, String text, Ink ink) {
        Drawn probe = Drawn.of(font.deriveFont(PROBE_SIZE), text, 0);
        if (probe == null) {
            return null;
        }
        double byHeight = ink.height() / (double) probe.height();
        double byLength = ink.width() / (double) probe.width();
        Look best = fitted(font, face, text, ink, probe, byHeight, true);
        if (byLength / byHeight > 0.8 && byLength / byHeight < 1.25) {
            for (double k : new double[] {byLength, (byHeight + byLength) / 2}) {
                Look look = fitted(font, face, text, ink, probe, k, k != byLength);
                if (look != null && (best == null || look.likeness() > best.likeness())) {
                    best = look;
                }
            }
        }
        return best;
    }

    /** The text drawn {@code k} times the probe's size, spaced to the scan's length if asked, laid over the scan. */
    private static Look fitted(Font font, SystemFonts.Face face, String text, Ink ink, Drawn probe, double k,
            boolean spaced) {
        double size = PROBE_SIZE * k;
        double natural = probe.width() * k;
        int letters = text.codePointCount(0, text.length());
        double spacing = spaced && letters > 1 ? (ink.width() - natural) / (letters - 1) : 0;
        spacing = Math.max(MIN_SPACING * size, Math.min(MAX_SPACING * size, spacing));
        // Drawn again at that size and spacing, to lay over the scan pixel for pixel.
        Drawn drawn = Drawn.of(font.deriveFont((float) size), text, (float) spacing);
        if (drawn == null) {
            return null;
        }
        double likeness = likeness(ink, drawn);
        // A font much wider or narrower than the scan needed spacing its letters were not set with: less likely.
        likeness *= Math.max(0.5, 1 - 0.5 * Math.abs(Math.log(ink.width() / Math.max(1.0, natural))));
        double originX = ink.left() - (drawn.left() - drawn.originX());
        double baselineY = ink.top() + (drawn.baseline() - drawn.top());
        return new Look(face, size, originX, baselineY, spacing, likeness, drawn.coverage());
    }

    /**
     * How much of the two inks falls on the other, each pixel allowed one pixel off: 1 when they cover each other,
     * 0 when nothing does. The drawing is laid with its top-left ink on the scan's.
     */
    static double likeness(Ink ink, Drawn drawn) {
        int w = ink.width();
        int h = ink.height();
        boolean[][] scan = new boolean[h][w];
        boolean[][] font = new boolean[h][w];
        int scanCount = 0;
        int fontCount = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                scan[y][x] = ink.solid()[ink.top() + y][ink.left() + x];
                font[y][x] = drawn.inkAt(drawn.left() + x, drawn.top() + y);
                scanCount += scan[y][x] ? 1 : 0;
                fontCount += font[y][x] ? 1 : 0;
            }
        }
        if (scanCount + fontCount == 0) {
            return 0;
        }
        int matched = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (scan[y][x] && near(font, x, y)) {
                    matched++;
                }
                if (font[y][x] && near(scan, x, y)) {
                    matched++;
                }
            }
        }
        return matched / (double) (scanCount + fontCount);
    }

    private static boolean near(boolean[][] mask, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int yy = y + dy;
                int xx = x + dx;
                if (yy >= 0 && yy < mask.length && xx >= 0 && xx < mask[yy].length && mask[yy][xx]) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Text drawn in black on white: its ink, the rectangle around it, where its baseline started, and how much of
     * that rectangle it darkens (0 to 1, the soft edges in part).
     */
    record Drawn(boolean[][] ink, int left, int top, int right, int bottom, int originX, int baseline,
            double coverage) {

        /** Null when the text leaves no ink. */
        static Drawn of(Font font, String text, float spacing) {
            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D measure = probe.createGraphics();
            int advance;
            int ascent;
            int descent;
            try {
                measure.setFont(font);
                advance = measure.getFontMetrics().stringWidth(text);
                ascent = measure.getFontMetrics().getMaxAscent();
                descent = measure.getFontMetrics().getMaxDescent();
            } finally {
                measure.dispose();
            }
            int letters = text.codePointCount(0, text.length());
            int width = (int) Math.ceil(advance + Math.max(0, spacing) * letters) + 2 * PROBE_MARGIN;
            int height = ascent + descent + 2 * PROBE_MARGIN;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = image.createGraphics();
            int originX = PROBE_MARGIN;
            int baseline = PROBE_MARGIN + ascent;
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, width, height);
                g.setColor(Color.BLACK);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                g.setFont(font);
                if (spacing == 0) {
                    g.drawString(text, originX, baseline);
                } else {
                    // Letter by letter, as the PDF's character spacing places them.
                    float x = originX;
                    for (int i = 0; i < text.length(); ) {
                        int cp = text.codePointAt(i);
                        String letter = new String(Character.toChars(cp));
                        g.drawString(letter, x, baseline);
                        x += (float) font.getStringBounds(letter, g.getFontRenderContext()).getWidth() + spacing;
                        i += Character.charCount(cp);
                    }
                }
            } finally {
                g.dispose();
            }
            boolean[][] ink = new boolean[height][width];
            int left = Integer.MAX_VALUE;
            int top = Integer.MAX_VALUE;
            int right = -1;
            int bottom = -1;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if ((image.getRGB(x, y) & 0xFF) < 128) {
                        ink[y][x] = true;
                        left = Math.min(left, x);
                        top = Math.min(top, y);
                        right = Math.max(right, x);
                        bottom = Math.max(bottom, y);
                    }
                }
            }
            if (right < 0) {
                return null;
            }
            double dark = 0;
            for (int y = top; y <= bottom; y++) {
                for (int x = left; x <= right; x++) {
                    dark += 1 - (image.getRGB(x, y) & 0xFF) / 255.0;
                }
            }
            double coverage = dark / ((right - left + 1) * (double) (bottom - top + 1));
            return new Drawn(ink, left, top, right, bottom, originX, baseline, coverage);
        }

        int width() {
            return right - left + 1;
        }

        int height() {
            return bottom - top + 1;
        }

        boolean inkAt(int x, int y) {
            return y >= 0 && y < ink.length && x >= 0 && x < ink[y].length && ink[y][x];
        }
    }

    /** The face as a font Java can draw with; empty when its file cannot be read that way. */
    static Optional<Font> awtFont(SystemFonts.Face face) {
        return AWT_FONTS.computeIfAbsent(key(face), k -> {
            try {
                if (face.collectionFontName() == null) {
                    return Optional.of(Font.createFont(Font.TRUETYPE_FONT, face.file().toFile()));
                }
                for (Font font : Font.createFonts(face.file().toFile())) {
                    if (face.collectionFontName().equals(font.getPSName())
                            || face.collectionFontName().equals(font.getFontName())) {
                        return Optional.of(font);
                    }
                }
                return Optional.empty();
            } catch (IOException | FontFormatException | RuntimeException unreadable) {
                return Optional.empty();
            }
        });
    }
}
