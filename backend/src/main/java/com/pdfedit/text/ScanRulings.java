package com.pdfedit.text;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
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
    /** Darker than this (0 black - 255 white) is ink. */
    private static final int INK = 150;
    /** A run of ink at least this long (pixels) can be part of a border; shorter ones are letters. */
    private static final int MIN_RUN = 18;
    /** A border is at least this long (points). */
    private static final float MIN_LINE = 28;
    /** Runs this close across the line (pixels) belong to one line: its thickness, or its drift when askew. */
    private static final float DRIFT = 2.5f;
    /** A break this short (pixels) in a line is a speck of the scan, not the line's end. */
    private static final int GAP = 4;
    /** Lines stop a little short of the borders they meet: they are made this much longer at each end (points). */
    private static final float REACH = 1.5f;

    private ScanRulings() {
    }

    public static RulingLines.Lines of(PDDocument document, int pageIndex) throws IOException {
        float dpi = com.pdfedit.service.RenderLimits.cappedDpi(document.getPage(pageIndex).getCropBox(), DPI);
        BufferedImage image = new PDFRenderer(document).renderImageWithDPI(pageIndex, dpi, ImageType.GRAY);
        return of(image, 72f / dpi);
    }

    /** The lines in a page picture, scaled to points by {@code pointsPerPixel}. */
    static RulingLines.Lines of(BufferedImage image, float pointsPerPixel) {
        int w = image.getWidth();
        int h = image.getHeight();
        boolean[] ink = new boolean[w * h];
        Raster raster = image.getRaster();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            raster.getSamples(0, y, w, 1, 0, row);
            for (int x = 0; x < w; x++) {
                ink[y * w + x] = row[x] < INK;
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
        return new RulingLines.Lines(horizontal, vertical);
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
        return lines;
    }
}
