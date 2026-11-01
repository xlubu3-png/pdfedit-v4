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
     * A gap this much wider (a share of the letters' height) than between the letters of a word, and at least
     * {@link #WORD_GAP} wide, is a space between words, whatever recognition read.
     */
    private static final double WORD_GAP_MORE = 0.3;
    private static final double WORD_GAP = 0.4;
    /** A space read where the gap is at most this much wider than between the letters of a word is not one. */
    private static final double NO_GAP_MORE = 0.05;
    /**
     * The letters' height is taken as at most this many times the middle of each letter's own: a long line of a
     * tilted scan is much taller than its letters.
     */
    private static final double TALLEST = 1.25;
    /** Letter spacing kept within these shares of the font size: more says the font is wrong, not spaced. */
    private static final double MIN_SPACING = -0.12;
    private static final double MAX_SPACING = 0.6;

    /**
     * A drawn pixel darker than this (0-255, drawn black on white) is ink: a quarter of the way to black, as a
     * scan's solid ink is told (a fifth or so of the way from its paper to its ink). Told much darker, the thin
     * strokes of a 명조 face would vanish where the scan's are kept, and a heavier face would look more like it.
     */
    private static final int DRAWN_INK = 192;

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
     * The text spaced as the scan is: a space put between two letters where the stretch without ink between them is
     * clearly wider than between the letters of a word, one taken out where it is clearly no wider; elsewhere the
     * spaces stay as recognition read them. Recognition reads spaces off those gaps too, but more coarsely: a font
     * set wide gets spaces inside its words, a tight one loses some between them.
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
        List<Boolean> spaceAfter = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != ' ') {
                letters.add(text.charAt(i));
                places.add(centers.get(i) * (double) width);
                spaceAfter.add(false);
            } else if (!spaceAfter.isEmpty()) {
                spaceAfter.set(spaceAfter.size() - 1, true);
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
        double height = letterHeight(ink, places);
        double space = Math.max(usual + WORD_GAP_MORE * height, WORD_GAP * height);
        double noSpace = usual + NO_GAP_MORE * height;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < letters.size(); i++) {
            out.append(letters.get(i));
            // Letters with no gap at all between them (a rule running under the line, letters run together) say
            // nothing of a space: recognition's is kept.
            if (i < gaps.length && (gaps[i] >= space || (spaceAfter.get(i) && (gaps[i] > noSpace || gaps[i] == 0)))) {
                out.append(' ');
            }
        }
        return out.toString();
    }

    /**
     * How tall the line's letters are (pixels): the line's ink, but at most {@link #TALLEST} times the middle of the
     * letters' own heights, each told by the ink around its middle - a long line of a tilted scan is much taller
     * than its letters.
     *
     * @param places the middle of each letter along the line, pixels
     */
    static double letterHeight(Ink ink, List<Double> places) {
        if (places.size() < 2) {
            return ink.height();
        }
        double[] heights = new double[places.size()];
        for (int i = 0; i < places.size(); i++) {
            double half = (i + 1 < places.size() ? Math.abs(places.get(i + 1) - places.get(i))
                    : Math.abs(places.get(i) - places.get(i - 1))) / 2;
            heights[i] = inkHeight(ink, (int) Math.round(places.get(i) - half), (int) Math.round(places.get(i) + half));
        }
        java.util.Arrays.sort(heights);
        return Math.min(ink.height(), TALLEST * heights[heights.length / 2]);
    }

    /** How tall the ink is between columns {@code from} and {@code to}; the whole line's height when it has none there. */
    private static int inkHeight(Ink ink, int from, int to) {
        int top = Integer.MAX_VALUE;
        int bottom = -1;
        for (int y = ink.top(); y <= ink.bottom(); y++) {
            for (int x = Math.max(ink.left(), from); x <= Math.min(ink.right(), to); x++) {
                if (ink.solid()[y][x]) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                    break;
                }
            }
        }
        return bottom < 0 ? ink.height() : bottom - top + 1;
    }

    /**
     * How each face that can draw {@code text} draws it over the scan: sized, placed and spaced, and how alike.
     * When the scan's words can be told apart (as many stretches of ink between the widest gaps as the text has
     * words, or where {@code middles} says), they are compared word by word, each of the font's words set as wide as the scanned one and laid
     * over it: laid over the whole line at once, a font whose letters are a pixel or two narrower or wider than
     * the scan's drifts off them along the line, and every font looks about equally unlike it. The size is then
     * the one that makes the font's words as tall as the scan's (the line's height alone is thrown off by
     * brackets and other marks that reach above and below the letters in one font and not in another).
     */
    static Map<SystemFonts.Face, Look> looks(String text, Ink ink, List<SystemFonts.Face> faces) {
        return looks(text, ink, faces, null);
    }

    /**
     * The same, the words told apart where recognition saw their letters when {@code middles} says so: the
     * widest gap between the last letter of a word and the first of the next. A word the scan has no gap after
     * (or before) is left out of the likeness.
     *
     * @param middles the middle of each letter (not space) of {@code text} along the line, pixels of {@code ink}'s
     *                picture; or null
     */
    static Map<SystemFonts.Face, Look> looks(String text, Ink ink, List<SystemFonts.Face> faces, double[] middles) {
        List<String> words = java.util.Arrays.stream(text.split(" ")).filter(w -> !w.isEmpty()).toList();
        List<int[]> spans = middles == null ? wordSpans(ink, words.size()) : wordSpans(ink, words, middles);
        // Each face is drawn and compared on its own, all at once: a page's first rewrite compares a dozen faces
        // over several lines.
        List<Look> found = faces.parallelStream().map(face -> {
            Font font = awtFont(face).orElse(null);
            if (font == null || font.canDisplayUpTo(text) != -1) {
                return null;
            }
            Look look = look(font, face, text, ink);
            return look != null && spans != null ? byWords(font, face, text, ink, words, spans, look) : look;
        }).toList();
        Map<SystemFonts.Face, Look> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < faces.size(); i++) {
            if (found.get(i) != null) {
                out.put(faces.get(i), found.get(i));
            }
        }
        return out;
    }

    /**
     * Where each of {@code count} words of the scan lies along it (first and last column of its ink): the ink
     * between the {@code count - 1} widest gaps. Null when there are not that many gaps.
     */
    static List<int[]> wordSpans(Ink ink, int count) {
        if (count < 1) {
            return null;
        }
        boolean[] inked = new boolean[ink.right() + 1];
        for (int x = ink.left(); x <= ink.right(); x++) {
            for (int y = ink.top(); y <= ink.bottom() && !inked[x]; y++) {
                inked[x] = ink.solid()[y][x];
            }
        }
        List<int[]> gaps = new ArrayList<>();
        for (int x = ink.left(); x <= ink.right(); ) {
            if (inked[x]) {
                x++;
                continue;
            }
            int from = x;
            while (x <= ink.right() && !inked[x]) {
                x++;
            }
            gaps.add(new int[] {from, x - 1});
        }
        if (gaps.size() < count - 1) {
            return null;
        }
        List<int[]> cuts = gaps.stream()
                .sorted(java.util.Comparator.comparingInt((int[] g) -> -(g[1] - g[0])))
                .limit(count - 1L)
                .sorted(java.util.Comparator.comparingInt((int[] g) -> g[0]))
                .toList();
        List<int[]> spans = new ArrayList<>();
        int from = ink.left();
        for (int[] cut : cuts) {
            spans.add(new int[] {from, cut[0] - 1});
            from = cut[1] + 1;
        }
        spans.add(new int[] {from, ink.right()});
        return spans;
    }

    /**
     * Where each word lies along the scan (first and last column of its ink), cut at the widest gap between the
     * middles of its last letter and the next word's first; null for a word with no gap at one side, and null as a
     * whole when the middles are not one for each letter.
     */
    static List<int[]> wordSpans(Ink ink, List<String> words, double[] middles) {
        int letters = words.stream().mapToInt(w -> w.codePointCount(0, w.length())).sum();
        if (middles.length != letters || words.isEmpty()) {
            return null;
        }
        boolean[] inked = new boolean[ink.right() + 1];
        for (int x = ink.left(); x <= ink.right(); x++) {
            for (int y = ink.top(); y <= ink.bottom() && !inked[x]; y++) {
                inked[x] = ink.solid()[y][x];
            }
        }
        // cuts[i]: the gap after word i (first and last empty column), or null when there is none.
        int[][] cuts = new int[words.size() - 1][];
        int last = -1;
        for (int i = 0; i + 1 < words.size(); i++) {
            last += words.get(i).codePointCount(0, words.get(i).length());
            int from = (int) Math.max(ink.left(), Math.round(middles[last]));
            int to = (int) Math.min(ink.right(), Math.round(middles[last + 1]));
            for (int x = from; x <= to; ) {
                if (inked[x]) {
                    x++;
                    continue;
                }
                int start = x;
                while (x <= ink.right() && !inked[x]) {
                    x++;
                }
                if (cuts[i] == null || x - 1 - start > cuts[i][1] - cuts[i][0]) {
                    cuts[i] = new int[] {start, x - 1};
                }
            }
        }
        List<int[]> spans = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            int[] before = i == 0 ? new int[] {ink.left() - 1, ink.left() - 1} : cuts[i - 1];
            int[] after = i == words.size() - 1 ? new int[] {ink.right() + 1, ink.right() + 1} : cuts[i];
            spans.add(before == null || after == null || after[0] - 1 < before[1] + 1 ? null
                    : new int[] {before[1] + 1, after[0] - 1});
        }
        return spans;
    }

    /**
     * Where each letter (not space) of {@code read} was seen along the line, pixels of a picture {@code width}
     * wide; null when recognition gave no places.
     */
    static double[] letterMiddles(String read, List<Float> centers, int width) {
        if (centers == null || centers.size() != read.length()) {
            return null;
        }
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < read.length(); i++) {
            if (!Character.isWhitespace(read.charAt(i))) {
                out.add(centers.get(i) * (double) width);
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * The look sized so the font's words are as tall as the scan's (the middle of their ratios), and how alike
     * the two are word by word; {@code line} as it was when the words cannot be drawn.
     */
    private static Look byWords(Font font, SystemFonts.Face face, String text, Ink ink, List<String> words,
            List<int[]> spans, Look line) {
        Font sized = font.deriveFont((float) line.sizePx());
        List<Double> ratios = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            if (spans.get(i) == null) {
                continue;
            }
            Drawn drawn = Drawn.of(sized, words.get(i), 0);
            int[] box = inkBox(ink, spans.get(i));
            if (drawn != null && box != null) {
                ratios.add((box[3] - box[1] + 1) / (double) drawn.height());
            }
        }
        if (ratios.isEmpty()) {
            return line;
        }
        ratios.sort(null);
        double ratio = ratios.get(ratios.size() / 2);
        Look look = line;
        if (Math.abs(ratio - 1) > 0.02 && ratio > MIN_WORD_RATIO && ratio < MAX_WORD_RATIO) {
            Drawn probe = Drawn.of(font.deriveFont(PROBE_SIZE), text, 0);
            Look resized = probe == null ? null
                    : fitted(font, face, text, ink, probe, line.sizePx() * ratio / PROBE_SIZE, true);
            look = resized != null ? resized : line;
        }
        double likeness = wordLikeness(ink, font.deriveFont((float) look.sizePx()), words, spans);
        return new Look(face, look.sizePx(), look.originX(), look.baselineY(), look.spacingPx(), likeness,
                look.coverage());
    }

    /** The rectangle around the ink of {@code span} (left, top, right, bottom); null when it has none. */
    private static int[] inkBox(Ink ink, int[] span) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = -1;
        int bottom = -1;
        for (int y = ink.top(); y <= ink.bottom(); y++) {
            for (int x = span[0]; x <= span[1]; x++) {
                if (ink.solid()[y][x]) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        return right < 0 ? null : new int[] {left, top, right, bottom};
    }

    /**
     * How far the words' height may set the size apart from the line's: a long line of a tilted scan is much
     * taller than its letters.
     */
    private static final double MIN_WORD_RATIO = 0.4;
    private static final double MAX_WORD_RATIO = 1.6;

    /** Pixels the font's word may be moved each way from the scanned one's middle to find where it lies best. */
    private static final int SHIFT = 2;

    /**
     * How alike the scan's words and the font's are, each of the font's words set as wide as its scanned one
     * (by letter spacing) and laid over it where it falls best: of all their ink, how much falls on the other's -
     * a pixel off counting half.
     */
    static double wordLikeness(Ink ink, Font font, List<String> words, List<int[]> spans) {
        long matched = 0;
        long total = 0;
        for (int i = 0; i < words.size(); i++) {
            if (spans.get(i) == null) {
                continue;
            }
            int[] box = inkBox(ink, spans.get(i));
            String word = words.get(i);
            Drawn natural = Drawn.of(font, word, 0);
            if (box == null || natural == null) {
                continue;
            }
            int letters = word.codePointCount(0, word.length());
            int scanWidth = box[2] - box[0] + 1;
            double spacing = letters > 1 ? (scanWidth - natural.width()) / (double) (letters - 1) : 0;
            spacing = Math.max(MIN_SPACING * font.getSize2D(), Math.min(MAX_SPACING * font.getSize2D(), spacing));
            Drawn drawn = Math.abs(spacing) < 0.5 ? natural : Drawn.of(font, word, (float) spacing);
            if (drawn == null) {
                continue;
            }
            // Both inks on one grid, with room to move the font's.
            int w = Math.max(scanWidth, drawn.width()) + 2 * SHIFT + 2;
            int h = Math.max(box[3] - box[1] + 1, drawn.height()) + 2 * SHIFT + 2;
            boolean[][] scan = new boolean[h][w];
            int scanX = (w - scanWidth) / 2;
            int scanY = (h - (box[3] - box[1] + 1)) / 2;
            int scanCount = 0;
            for (int y = box[1]; y <= box[3]; y++) {
                for (int x = box[0]; x <= box[2]; x++) {
                    if (ink.solid()[y][x]) {
                        scan[y - box[1] + scanY][x - box[0] + scanX] = true;
                        scanCount++;
                    }
                }
            }
            boolean[][] scanNear = dilated(scan);
            int fontX = (w - drawn.width()) / 2;
            int fontY = (h - drawn.height()) / 2;
            boolean[][] font0 = new boolean[h][w];
            int fontCount = 0;
            for (int y = drawn.top(); y <= drawn.bottom(); y++) {
                for (int x = drawn.left(); x <= drawn.right(); x++) {
                    if (drawn.inkAt(x, y)) {
                        font0[y - drawn.top() + fontY][x - drawn.left() + fontX] = true;
                        fontCount++;
                    }
                }
            }
            boolean[][] fontNear = dilated(font0);
            long best = 0;
            for (int dy = -SHIFT; dy <= SHIFT; dy++) {
                for (int dx = -SHIFT; dx <= SHIFT; dx++) {
                    long m = 0;
                    for (int y = Math.max(0, dy); y < Math.min(h, h + dy); y++) {
                        boolean[] fontRow = font0[y - dy];
                        boolean[] fontNearRow = fontNear[y - dy];
                        boolean[] scanRow = scan[y];
                        boolean[] scanNearRow = scanNear[y];
                        for (int x = Math.max(0, dx); x < Math.min(w, w + dx); x++) {
                            // A pixel falling on the other ink counts whole, one a pixel off half: faces alike in
                            // build (two 명조) are told apart by where exactly their strokes run.
                            if (scanRow[x] && fontNearRow[x - dx]) {
                                m += fontRow[x - dx] ? 2 : 1;
                            }
                            if (fontRow[x - dx] && scanNearRow[x]) {
                                m += scanRow[x] ? 2 : 1;
                            }
                        }
                    }
                    best = Math.max(best, m);
                }
            }
            matched += best;
            total += 2L * (scanCount + fontCount);
        }
        return total == 0 ? 0 : matched / (double) total;
    }

    /** The mask grown by a pixel each way (touching corners too). */
    private static boolean[][] dilated(boolean[][] mask) {
        boolean[][] out = new boolean[mask.length][];
        for (int y = 0; y < mask.length; y++) {
            out[y] = new boolean[mask[y].length];
            for (int x = 0; x < mask[y].length; x++) {
                out[y][x] = near(mask, x, y);
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
                    if ((image.getRGB(x, y) & 0xFF) < DRAWN_INK) {
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
