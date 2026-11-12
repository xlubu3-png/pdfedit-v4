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
 * pixels. A page scanned askew is turned straight first ({@link Deskew}). Then three kinds of border are looked for:
 * <ul>
 * <li>long thin straight runs of dark, colourless ink (a red stamp is not a border);</li>
 * <li>dotted or dashed lines: rows of short dots close together, where they meet a solid border;</li>
 * <li>the edges of shaded areas (a grey header row), which have no line drawn round them.</li>
 * </ul>
 * Letters' strokes are far shorter than a cell's side, thicker strokes (a bold title) and slivers of white inside a
 * shaded band are left out, and a short line is kept only where both its ends meet borders across it and it is not
 * inside a recognized word. Positions are in points from the top-left corner of the page's visible area, as
 * {@link RulingLines} gives them (in the straightened page's frame when it was turned).
 * <p>
 * The thresholds and the defences against false lines follow kordoc's ocr/ruling-lines.ts (MIT, Copyright (c) 2026
 * chrisryugj), measured there on many real scanned forms.
 */
public final class ScanRulings {

    /** Resolution the page is looked at: enough to tell a 1-pixel line, quick even for A3. */
    static final float DPI = 120;
    /**
     * Darker than this (0 black - 255 white) is ink. Light: a thin border falling between two pixels comes out as
     * two light grey ones (192 - 207); shaded cells, darker still, are told from borders by their thickness.
     */
    private static final int INK = 205;
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
    /** A border is at most this thick (points, on average): thicker is a bold stroke, a band of text or a fill. */
    private static final float MAX_THICKNESS_PT = 2.5f;
    /** Runs this close across the line (pixels) belong to one line: its thickness, or its drift when askew. */
    private static final float DRIFT = 2.5f;
    /** A break this short (pixels) in a line is a speck of the scan, not the line's end. */
    private static final int GAP = 4;
    /** Lines stop a little short of the borders they meet: they are made this much longer at each end (points). */
    private static final float REACH = 1.5f;
    /** A line with this share of ink just beside it on both sides is a sliver inside a shaded band, not a border. */
    private static final double SURROUNDED = 0.35;

    /** A dot or dash of a dotted line is at most this long (points); the gaps between at most this. */
    private static final float DOT_MAX_DASH_PT = 4;
    private static final float DOT_MAX_GAP_PT = 1.3f;
    /** At least this many dots in a row make a dotted line (fewer is a row of full stops). */
    private static final int DOT_MIN_COUNT = 8;
    /** A dotted line meeting a border at one end only must be at least this long (points). */
    private static final float DOT_ONE_SIDE_PT = 60;

    /** A shaded area is this much darker (0 - 255) than the page around it. */
    private static final int FILL_DELTA = 10;
    /** ... and goes on inward at least this far (points) from its edge. */
    private static final float FILL_DEPTH_PT = 4;

    /** A shade lighter than this (0 - 255) is a fill when it is even round a pixel; darker is ink whatever it is. */
    private static final int FILL_DARKEST = 140;
    /** How far (pixels) round a pixel the shade must be the same, to this much (0 - 255), for it to be a fill. */
    private static final int FLAT_REACH = 3;
    private static final int FLAT = 12;

    /** A strip of paper between two dark fills is at most this thick (points) to be the border between them. */
    private static final float FILL_GAP_PT = 3;

    private enum Kind { SOLID, DOTTED, FILL }

    private ScanRulings() {
    }

    /**
     * @param words the boxes of the text recognized on the page ({left, top, width, height}, points from the
     *              top-left): a short stroke inside one is part of a letter, not a border; empty when none
     */
    public static RulingLines.Lines of(PDDocument document, int pageIndex, List<float[]> words) throws IOException {
        float dpi = com.pdfedit.service.RenderLimits.cappedDpi(document.getPage(pageIndex).getCropBox(), DPI);
        BufferedImage image = new PDFRenderer(document).renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
        return straightened(image, 72f / dpi, words);
    }

    /** The lines of a page picture turned straight first when it was scanned askew. */
    static RulingLines.Lines straightened(BufferedImage image, float pointsPerPixel, List<float[]> words) {
        double turn = Deskew.angle(image);
        if (turn == 0) {
            return of(image, pointsPerPixel, words);
        }
        double px = image.getWidth() * pointsPerPixel / 2;
        double py = image.getHeight() * pointsPerPixel / 2;
        double r = Math.toRadians(turn);
        List<float[]> turnedWords = words.stream().map(w -> {
            double cx = w[0] + w[2] / 2 - px;
            double cy = w[1] + w[3] / 2 - py;
            double x = px + cx * Math.cos(r) - cy * Math.sin(r);
            double y = py + cx * Math.sin(r) + cy * Math.cos(r);
            return new float[] {(float) (x - w[2] / 2), (float) (y - w[3] / 2), w[2], w[3]};
        }).toList();
        RulingLines.Lines lines = of(Deskew.turned(image, turn), pointsPerPixel, turnedWords);
        return new RulingLines.Lines(lines.horizontal(), lines.vertical(), turn, px, py);
    }

    /** The lines in a page picture, scaled to points by {@code pointsPerPixel}. */
    static RulingLines.Lines of(BufferedImage image, float pointsPerPixel) {
        return of(image, pointsPerPixel, List.of());
    }

    static RulingLines.Lines of(BufferedImage image, float pointsPerPixel, List<float[]> words) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] grey = new int[w * h];
        boolean[] ink = new boolean[w * h];
        int[] histogram = new int[256];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int r = (row[x] >> 16) & 0xff;
                int g = (row[x] >> 8) & 0xff;
                int b = row[x] & 0xff;
                int luma = (r * 299 + g * 587 + b * 114) / 1000;
                int colour = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                grey[y * w + x] = luma;
                // Dark and without colour: a red stamp or a coloured mark is not a table's border.
                ink[y * w + x] = luma < INK && colour < COLOURED;
                histogram[luma]++;
            }
        }
        // A grey shaded area (a header row) is not ink, however dark: the same grey all round a pixel is a fill, and
        // the borders drawn across it, and its letters, stand out from it (kordoc's shaded header bands).
        // The inside of a fill is the same grey on all four sides; its rim (beside a border drawn over it, at its
        // edges) is what lies within reach of the inside in the same grey. A thin grey line is the same grey only
        // along itself, so it stays ink.
        boolean[] inside = new boolean[w * h];
        for (int y = FLAT_REACH; y < h - FLAT_REACH; y++) {
            for (int x = FLAT_REACH; x < w - FLAT_REACH; x++) {
                int i = y * w + x;
                int v = grey[i];
                inside[i] = ink[i] && v >= FILL_DARKEST
                        && Math.abs(grey[i - FLAT_REACH] - v) <= FLAT
                        && Math.abs(grey[i + FLAT_REACH] - v) <= FLAT
                        && Math.abs(grey[i - FLAT_REACH * w] - v) <= FLAT
                        && Math.abs(grey[i + FLAT_REACH * w] - v) <= FLAT;
            }
        }
        boolean[] fill = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int v = grey[i];
                if (!ink[i] || v < FILL_DARKEST) {
                    continue;
                }
                search:
                for (int yy = Math.max(0, y - FLAT_REACH); yy <= Math.min(h - 1, y + FLAT_REACH); yy++) {
                    for (int xx = Math.max(0, x - FLAT_REACH); xx <= Math.min(w - 1, x + FLAT_REACH); xx++) {
                        int j = yy * w + xx;
                        if (inside[j] && Math.abs(grey[j] - v) <= FLAT) {
                            fill[i] = true;
                            break search;
                        }
                    }
                }
            }
        }
        for (int i = 0; i < ink.length; i++) {
            ink[i] &= !fill[i];
        }
        // The paper: the commonest light shade (white, or the cream of an old form).
        int paper = 255;
        for (int v = 200, most = -1; v < 256; v++) {
            if (histogram[v] > most) {
                most = histogram[v];
                paper = v;
            }
        }
        Page page = new Page(w, h, grey, ink, paper, pointsPerPixel);

        List<Found> found = new ArrayList<>();
        for (boolean alongX : new boolean[] {true, false}) {
            for (Line line : join(solidRuns(page, alongX))) {
                if (line.thickness() <= MAX_THICKNESS_PT / pointsPerPixel && !surrounded(page, line, alongX)) {
                    found.add(new Found(alongX, Kind.SOLID, line));
                }
            }
            for (Line line : join(dottedRuns(page, alongX))) {
                found.add(new Found(alongX, Kind.DOTTED, line));
            }
            for (Line line : join(fillEdgeRuns(page, alongX))) {
                found.add(new Found(alongX, Kind.FILL, line));
            }
            for (Line line : join(fillGapRuns(page, alongX))) {
                found.add(new Found(alongX, Kind.FILL, line));
            }
        }

        List<Placed> placed = new ArrayList<>();
        for (Found f : found) {
            float across = (f.line.across() + 0.5f) * pointsPerPixel;
            float from = f.line.from * pointsPerPixel - REACH;
            float to = (f.line.to + 1) * pointsPerPixel + REACH;
            if (to - from >= MIN_LINE) {
                placed.add(new Placed(f.alongX, f.kind, across, from, to));
            }
        }
        placed = acrossGaps(placed);
        // Short lines only where they meet borders at both ends, dotted ones where they meet solid borders; against
        // the lines as found, not as kept.
        List<Placed> all = List.copyOf(placed);
        List<RulingLines.Horizontal> horizontal = new ArrayList<>();
        List<RulingLines.Vertical> vertical = new ArrayList<>();
        for (Placed p : placed) {
            float length = p.to - p.from;
            // The longest border each end meets (0 for none).
            float start = meets(all, p, p.from, p.kind == Kind.DOTTED);
            float end = meets(all, p, p.to, p.kind == Kind.DOTTED);
            boolean keep = switch (p.kind) {
                case DOTTED -> (start > 0 && end > 0) || ((start > 0 || end > 0) && length >= DOT_ONE_SIDE_PT);
                // A short one meets borders at both ends, one of them long: a cell's side meets the table's rows,
                // while the strokes of a large letter (an E's spine and arms) only meet each other.
                case SOLID, FILL -> length >= LONG_LINE
                        || (start > 0 && end > 0 && Math.max(start, end) >= LONG_LINE && !inWord(words, p));
            };
            if (!keep) {
                continue;
            }
            if (p.alongX) {
                horizontal.add(new RulingLines.Horizontal(p.across, p.from, p.to));
            } else {
                vertical.add(new RulingLines.Vertical(p.across, p.from, p.to));
            }
        }
        return new RulingLines.Lines(horizontal, vertical);
    }

    private record Page(int w, int h, int[] grey, boolean[] ink, int paper, float pointsPerPixel) {
        boolean ink(boolean alongX, int along, int across) {
            return alongX ? ink[across * w + along] : ink[along * w + across];
        }

        int grey(boolean alongX, int along, int across) {
            return alongX ? grey[across * w + along] : grey[along * w + across];
        }

        int length(boolean alongX) {
            return alongX ? w : h;
        }

        int breadth(boolean alongX) {
            return alongX ? h : w;
        }
    }

    /**
     * The edges of two filled cells facing each other across a thin gap of paper (a coloured header column whose
     * cells are parted by a hair of white) as one border along the gap's middle, not two a hair apart with a
     * sliver of a row between them.
     */
    private static List<Placed> acrossGaps(List<Placed> placed) {
        List<Placed> out = new ArrayList<>();
        boolean[] used = new boolean[placed.size()];
        for (int i = 0; i < placed.size(); i++) {
            Placed p = placed.get(i);
            if (used[i]) {
                continue;
            }
            if (p.kind == Kind.FILL) {
                for (int j = i + 1; j < placed.size(); j++) {
                    Placed o = placed.get(j);
                    if (used[j] || o.kind != Kind.FILL || o.alongX != p.alongX) {
                        continue;
                    }
                    float gap = Math.abs(o.across - p.across);
                    float shared = Math.min(p.to, o.to) - Math.max(p.from, o.from);
                    float longer = Math.max(p.to - p.from, o.to - o.from);
                    if (gap > 0 && gap <= FILL_GAP_PT + 1 && shared >= 0.8f * longer) {
                        p = new Placed(p.alongX, Kind.FILL, (p.across + o.across) / 2, Math.min(p.from, o.from), Math.max(p.to, o.to));
                        used[j] = true;
                        break;
                    }
                }
            }
            out.add(p);
        }
        return out;
    }

    private record Run(int across, int from, int to) {
    }

    private record Found(boolean alongX, Kind kind, Line line) {
    }

    /** A line in points: {@code across} is its y for a horizontal one, x for a vertical one. */
    private record Placed(boolean alongX, Kind kind, float across, float from, float to) {
    }

    /**
     * The length of the longest line across {@code p} (not dotted, when asked) passing near the point of {@code p}
     * at {@code along}; 0 when none does.
     */
    private static float meets(List<Placed> all, Placed p, float along, boolean solidOnly) {
        float longest = 0;
        for (Placed o : all) {
            if (o.alongX == p.alongX || (solidOnly && o.kind == Kind.DOTTED)) {
                continue;
            }
            if (Math.abs(o.across - along) <= MEET && p.across >= o.from - MEET && p.across <= o.to + MEET) {
                longest = Math.max(longest, o.to - o.from);
            }
        }
        return longest;
    }

    /**
     * Whether the line is a stroke of letters: its middle lies well inside one of the recognized words' boxes, or
     * - an upright one - more than half of it runs inside words' boxes, away from their left and right edges. The
     * upright strokes of the letters of two close lines join into one "line" as high as the cell, whose middle
     * falls in the gap between the lines; a real cell border does not run through words (kordoc).
     */
    private static boolean inWord(List<float[]> words, Placed p) {
        double mid = (p.from + p.to) / 2;
        double x = p.alongX ? mid : p.across;
        double y = p.alongX ? p.across : mid;
        double inside = 0;
        for (float[] w : words) {
            if (x > w[0] + WORD_EDGE && x < w[0] + w[2] - WORD_EDGE && y > w[1] && y < w[1] + w[3]) {
                return true;
            }
            if (!p.alongX && p.across > w[0] + WORD_EDGE && p.across < w[0] + w[2] - WORD_EDGE) {
                inside += Math.max(0, Math.min(p.to, w[1] + w[3]) - Math.max(p.from, w[1]));
            }
        }
        return inside > (p.to - p.from) / 2;
    }

    /** The runs of ink at least {@link #MIN_RUN} long along each row (or column). */
    private static List<Run> solidRuns(Page page, boolean alongX) {
        List<Run> runs = new ArrayList<>();
        int length = page.length(alongX);
        for (int a = 0; a < page.breadth(alongX); a++) {
            int start = -1;
            for (int i = 0; i <= length; i++) {
                boolean on = i < length && page.ink(alongX, i, a);
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
        return runs;
    }

    /** Rows of at least {@link #DOT_MIN_COUNT} short dots with short gaps between, each as one run. */
    private static List<Run> dottedRuns(Page page, boolean alongX) {
        float ppp = page.pointsPerPixel();
        int maxDash = Math.max(1, Math.round(DOT_MAX_DASH_PT / ppp));
        int maxGap = Math.max(1, Math.round(DOT_MAX_GAP_PT / ppp));
        List<Run> runs = new ArrayList<>();
        int length = page.length(alongX);
        for (int a = 0; a < page.breadth(alongX); a++) {
            int chainFrom = -1;
            int chainTo = -1;
            int dots = 0;
            int start = -1;
            for (int i = 0; i <= length; i++) {
                boolean on = i < length && page.ink(alongX, i, a);
                if (on && start < 0) {
                    start = i;
                } else if (!on && start >= 0) {
                    int dash = i - start;
                    if (dash <= maxDash && chainTo >= 0 && start - chainTo - 1 <= maxGap) {
                        chainTo = i - 1;
                        dots++;
                    } else {
                        if (dots >= DOT_MIN_COUNT) {
                            runs.add(new Run(a, chainFrom, chainTo));
                        }
                        chainFrom = dash <= maxDash ? start : -1;
                        chainTo = dash <= maxDash ? i - 1 : -1;
                        dots = dash <= maxDash ? 1 : 0;
                    }
                    start = -1;
                }
            }
            if (dots >= DOT_MIN_COUNT) {
                runs.add(new Run(a, chainFrom, chainTo));
            }
        }
        return runs;
    }

    /**
     * The edges of shaded areas: where the paper ends and a shade at least {@link #FILL_DELTA} darker begins and
     * goes on at least {@link #FILL_DEPTH_PT} inward, on either side. Ink (a drawn border, a letter) is not paper.
     */
    private static List<Run> fillEdgeRuns(Page page, boolean alongX) {
        int depth = Math.max(2, Math.round(FILL_DEPTH_PT / page.pointsPerPixel()));
        int shade = page.paper() - FILL_DELTA;
        List<Run> runs = new ArrayList<>();
        int length = page.length(alongX);
        int breadth = page.breadth(alongX);
        for (int a = 1; a < breadth - 1; a++) {
            for (int side : new int[] {1, -1}) {
                // The edge pixel is shaded, the one outside it paper, and it stays shaded `depth` inward.
                int start = -1;
                for (int i = 0; i <= length; i++) {
                    boolean edge = false;
                    int inner = a + side * depth;
                    if (i < length && inner >= 0 && inner < breadth && page.grey(alongX, i, a - side) > shade
                            && !page.ink(alongX, i, a - side)) {
                        // Shade, and no ink, all the way in: the grey fringe round a letter is not a shaded area.
                        edge = true;
                        for (int k = a; k != inner + side; k += side) {
                            if (page.grey(alongX, i, k) > shade || page.ink(alongX, i, k)) {
                                edge = false;
                                break;
                            }
                        }
                    }
                    if (edge && start < 0) {
                        start = i;
                    } else if (!edge && start >= 0) {
                        if (i - start >= MIN_RUN) {
                            runs.add(new Run(a, start, i - 1));
                        }
                        start = -1;
                    }
                }
            }
        }
        return runs;
    }

    /**
     * The thin gaps of paper between two darkly filled cells (a navy header column with white letters has no
     * border drawn between its cells, only a hair of white): a strip of paper at most {@link #FILL_GAP_PT} thick
     * with the fill going on at least {@link #FILL_DEPTH_PT} on both sides of it, as a run along its middle. The
     * strokes of white letters on the fill are such strips too, but short ones, and are left out like any short
     * line that meets no border (kordoc).
     */
    private static List<Run> fillGapRuns(Page page, boolean alongX) {
        int depth = Math.max(2, Math.round(FILL_DEPTH_PT / page.pointsPerPixel()));
        int maxGap = Math.max(1, Math.round(FILL_GAP_PT / page.pointsPerPixel()));
        List<Run> runs = new ArrayList<>();
        int length = page.length(alongX);
        int breadth = page.breadth(alongX);
        for (int a = depth; a < breadth - depth - 1; a++) {
            int start = -1;
            int middle = a;
            for (int i = 0; i <= length; i++) {
                int thick = 0;
                // `a` is the first row of paper under a fill; how thick the strip is before the fill goes on.
                if (i < length && !page.ink(alongX, i, a) && page.ink(alongX, i, a - 1)) {
                    int t = 1;
                    while (t <= maxGap && a + t < breadth && !page.ink(alongX, i, a + t)) {
                        t++;
                    }
                    if (t <= maxGap && a + t + depth <= breadth) {
                        thick = t;
                        for (int k = 1; k <= depth && thick > 0; k++) {
                            if (!page.ink(alongX, i, a - k) || !page.ink(alongX, i, a + t - 1 + k)) {
                                thick = 0;
                            }
                        }
                    }
                }
                if (thick > 0 && start < 0) {
                    start = i;
                    middle = a + (thick - 1) / 2;
                } else if (thick == 0 && start >= 0) {
                    if (i - start >= MIN_RUN) {
                        runs.add(new Run(middle, start, i - 1));
                    }
                    start = -1;
                }
            }
        }
        return runs;
    }

    /** Whether ink lies just beside the line on both sides over much of its length: a sliver inside a band. */
    private static boolean surrounded(Page page, Line line, boolean alongX) {
        int offset = (int) Math.ceil(line.thickness() / 2) + 2;
        int centre = Math.round(line.across());
        int before = centre - offset;
        int after = centre + offset;
        if (before < 0 || after >= page.breadth(alongX)) {
            return false;
        }
        int inkBefore = 0;
        int inkAfter = 0;
        int n = 0;
        for (int i = line.from; i <= line.to; i++) {
            inkBefore += page.ink(alongX, i, before) ? 1 : 0;
            inkAfter += page.ink(alongX, i, after) ? 1 : 0;
            n++;
        }
        return n > 0 && inkBefore >= SURROUNDED * n && inkAfter >= SURROUNDED * n;
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

        /** Pixels of ink per pixel of length: its average thickness. */
        float thickness() {
            return (float) count / (to - from + 1);
        }
    }

    /**
     * The runs joined into lines: a run continues a line when it starts no more than {@link #GAP} after the line's
     * end (or overlaps it) and lies within {@link #DRIFT} of the line's last run across.
     */
    private static List<Line> join(List<Run> runs) {
        List<Run> sorted = new ArrayList<>(runs);
        sorted.sort(Comparator.comparingInt(Run::from).thenComparingInt(Run::across));
        List<Line> lines = new ArrayList<>();
        List<Line> open = new ArrayList<>();
        for (Run run : sorted) {
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
