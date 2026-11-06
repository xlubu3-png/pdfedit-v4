package com.pdfedit.text;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * A scanned table's borders, found in the picture of the page: a scan has no lines drawn as lines, only dark
 * pixels, so long straight dark strokes across or down the page are taken as the borders. Letters' strokes are
 * far shorter than a cell's side and are left out; a sheet scanned a little askew is followed as its lines drift
 * by a pixel at a time. Positions are in points from the top-left corner of the page's visible area, as
 * {@link RulingLines} gives them.
 */
public final class ScanRulings {

    /** Resolution the page is looked at: enough to tell a 1-pixel line, quick even for A3. */
    static final float DPI = 120;
    /**
     * Darker than this (0 black - 255 white) is ink. Light: a thin border falls between two pixels and comes out
     * as two light grey ones; shaded cells, darker still, are told from borders by their thickness.
     */
    private static final int INK = 190;
    /** A pixel whose channels differ by this much is coloured (a stamp's red), not black or grey ink. */
    private static final int COLOURED = 90;
    /** A run of ink at least this long (pixels) can be part of a border; shorter ones are letters. */
    private static final int MIN_RUN = 14;
    /** A border is at least this long (points): one side of a small cell. */
    private static final float MIN_LINE = 10;
    /**
     * A border this long (points) or longer is taken as it is; a shorter one only when both its ends meet a border
     * across it, as a cell's sides do. A large letter's stroke (a title) does not.
     */
    private static final float LONG_LINE = 50;
    /** How near (points) a line's end must come to a border across it to meet it. */
    private static final float MEET = 4;
    /** A stroke this far (points) inside a recognized word's box is part of a letter. */
    private static final float WORD_EDGE = 1.5f;
    /** A line thicker than this (pixels, on average) is a shaded area or a blot, not a border. */
    private static final float MAX_THICKNESS = 5;
    /** Runs this close across the line (pixels) belong to one line: its thickness, or its drift when askew. */
    private static final float DRIFT = 2.5f;
    /** A break this short (pixels) in a line is a speck of the scan, not the line's end. */
    private static final int GAP = 4;
    /** Lines stop a little short of the borders they meet: they are made this much longer at each end (points). */
    private static final float REACH = 1.5f;

    private ScanRulings() {
    }

    /**
     * @param words the boxes of the text recognized on the page ({left, top, width, height}, points from the
     *              top-left): a short stroke inside one is part of a letter, not a border; empty when none
     */
    public static RulingLines.Lines of(PDDocument document, int pageIndex, List<float[]> words) throws IOException {
        float dpi = com.pdfedit.service.RenderLimits.cappedDpi(document.getPage(pageIndex).getCropBox(), DPI);
        BufferedImage image = new PDFRenderer(document).renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
        return of(image, 72f / dpi, words);
    }

    /** The lines in a page picture, scaled to points by {@code pointsPerPixel}. */
    static RulingLines.Lines of(BufferedImage image, float pointsPerPixel) {
        return of(image, pointsPerPixel, List.of());
    }

    static RulingLines.Lines of(BufferedImage image, float pointsPerPixel, List<float[]> words) {
        int w = image.getWidth();
        int h = image.getHeight();
        boolean[] ink = new boolean[w * h];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int r = (row[x] >> 16) & 0xff;
                int g = (row[x] >> 8) & 0xff;
                int b = row[x] & 0xff;
                // Dark and without colour: a red stamp or a coloured mark is not a table's border.
                int grey = (r * 299 + g * 587 + b * 114) / 1000;
                int colour = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                ink[y * w + x] = grey < INK && colour < COLOURED;
            }
        }
        List<RulingLines.Horizontal> horizontal = new ArrayList<>();
        for (Line line : lines(ink, w, h, true)) {
            horizontal.add(new RulingLines.Horizontal((line.across() + 0.5f) * pointsPerPixel,
                    line.from * pointsPerPixel - REACH, (line.to + 1) * pointsPerPixel + REACH));
        }
        List<RulingLines.Vertical> vertical = new ArrayList<>();
        for (Line line : lines(ink, w, h, false)) {
            vertical.add(new RulingLines.Vertical((line.across() + 0.5f) * pointsPerPixel,
                    line.from * pointsPerPixel - REACH, (line.to + 1) * pointsPerPixel + REACH));
        }
        float min = MIN_LINE;
        horizontal.removeIf(l -> l.x2() - l.x1() < min);
        vertical.removeIf(l -> l.y2() - l.y1() < min);
        // Short lines only where they meet borders at both ends (against the lines as found, not as kept).
        List<RulingLines.Horizontal> hs = List.copyOf(horizontal);
        List<RulingLines.Vertical> vs = List.copyOf(vertical);
        horizontal.removeIf(l -> l.x2() - l.x1() < LONG_LINE
                && (!(meetsVertical(vs, l.x1(), l.y()) && meetsVertical(vs, l.x2(), l.y()))
                        || inWord(words, (l.x1() + l.x2()) / 2, l.y())));
        vertical.removeIf(l -> l.y2() - l.y1() < LONG_LINE
                && (!(meetsHorizontal(hs, l.y1(), l.x()) && meetsHorizontal(hs, l.y2(), l.x()))
                        || inWord(words, l.x(), (l.y1() + l.y2()) / 2)));
        return new RulingLines.Lines(horizontal, vertical);
    }

    /** Whether the point lies well inside one of the recognized words' boxes. */
    private static boolean inWord(List<float[]> words, double x, double y) {
        for (float[] w : words) {
            if (x > w[0] + WORD_EDGE && x < w[0] + w[2] - WORD_EDGE && y > w[1] && y < w[1] + w[3]) {
                return true;
            }
        }
        return false;
    }

    /** Whether a vertical border passes near the point (x, y). */
    private static boolean meetsVertical(List<RulingLines.Vertical> lines, double x, double y) {
        return lines.stream().anyMatch(v -> Math.abs(v.x() - x) <= MEET && y >= v.y1() - MEET && y <= v.y2() + MEET);
    }

    /** Whether a horizontal border passes near the point (x, y), given as (y, x). */
    private static boolean meetsHorizontal(List<RulingLines.Horizontal> lines, double y, double x) {
        return lines.stream().anyMatch(h -> Math.abs(h.y() - y) <= MEET && x >= h.x1() - MEET && x <= h.x2() + MEET);
    }

    /** A line being followed: where it runs along (from - to) and the positions across of its runs. */
    private static final class Line {
        int from;
        int to;
        float last;
        double sum;
        long count;

        float across() {
            return (float) (sum / count);
        }
    }

    /**
     * The long runs of ink along rows ({@code alongX}) or columns, joined into lines: a run continues a line when
     * it starts no more than {@link #GAP} after the line's end (or overlaps it) and lies within {@link #DRIFT}
     * of the line's last run across.
     */
    private static List<Line> lines(boolean[] ink, int w, int h, boolean alongX) {
        int length = alongX ? w : h;
        int breadth = alongX ? h : w;
        record Run(int across, int from, int to) {
        }
        List<Run> runs = new ArrayList<>();
        for (int a = 0; a < breadth; a++) {
            int start = -1;
            for (int i = 0; i <= length; i++) {
                boolean on = i < length && (alongX ? ink[a * w + i] : ink[i * w + a]);
                if (on && start < 0) {
                    start = i;
                } else if (!on && start >= 0) {
                    if (i - start >= MIN_RUN) {
                        runs.add(new Run(a, start, i - 1));
                    }
                    start = -1;
                }
            }
        }
        runs.sort(Comparator.comparingInt(Run::from).thenComparingInt(Run::across));
        List<Line> lines = new ArrayList<>();
        List<Line> open = new ArrayList<>();
        for (Run run : runs) {
            Line joined = null;
            for (Line line : open) {
                if (Math.abs(line.last - run.across()) <= DRIFT && run.from() <= line.to + GAP) {
                    joined = line;
                    break;
                }
            }
            if (joined == null) {
                joined = new Line();
                joined.from = run.from();
                joined.to = run.to();
                open.add(joined);
                lines.add(joined);
            }
            joined.to = Math.max(joined.to, run.to());
            joined.last = run.across();
            int span = run.to() - run.from() + 1;
            joined.sum += (double) run.across() * span;
            joined.count += span;
            if (open.size() > 64) {
                // Lines that ended long before this run starts are done: keep the search short.
                int at = run.from();
                open.removeIf(l -> l.to + GAP < at);
            }
        }
        // A shaded area is ink in many rows at once: on average far thicker than a border.
        lines.removeIf(l -> l.count > MAX_THICKNESS * (l.to - l.from + 1));
        return lines;
    }
}
