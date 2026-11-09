package com.pdfedit.ocr;

import java.awt.image.BufferedImage;

/**
 * A line's picture with what does not belong to it painted out, for the reader: a table's border running down a
 * cell's edge (read as "|", "(" or ":" - "60.5" became "(.5", "0:36" became ":36"), a double or dotted border, the
 * letters of the next cell over the border, and the tips of the lines above and below a line set close (read as
 * a final consonant: "노동" became "논동"). The box is not made smaller, only its edges painted the paper's shade:
 * the reader then sees the letters at the same size. The approach and its thresholds follow kordoc's
 * ocr/line-split.ts edgeTrim (MIT, Copyright (c) 2026 chrisryugj).
 */
final class EdgeTrim {

    private EdgeTrim() {
    }

    /** The picture with its edges painted out, or null when nothing at its edges is to go. */
    static BufferedImage trimmed(BufferedImage crop) {
        int w = crop.getWidth();
        int h = crop.getHeight();
        if (w < 4 || h < 4) {
            return null;
        }
        int[] rgb = crop.getRGB(0, 0, w, h, null, 0, w);
        int[] gray = new int[rgb.length];
        int[] hist = new int[256];
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            gray[i] = (((p >> 16) & 0xff) * 299 + ((p >> 8) & 0xff) * 587 + (p & 0xff) * 114) / 1000;
            hist[gray[i]]++;
        }
        int threshold = otsu(hist, gray.length);
        long dark = 0;
        for (int v = 0; v <= threshold; v++) {
            dark += hist[v];
        }
        if (dark == 0 || dark == gray.length) {
            return null;
        }
        boolean darkInk = dark <= gray.length - dark;
        int[] keep = keep(gray, w, h, threshold, darkInk);
        if (keep == null) {
            return null;
        }
        int bg = median(hist, gray.length);
        int paper = (bg << 16) | (bg << 8) | bg;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean in = x >= keep[0] && x < keep[1] && y >= keep[2] && y < keep[3];
                out.setRGB(x, y, in ? rgb[y * w + x] : paper);
            }
        }
        return out;
    }

    /** What to keep {x0, x1, y0, y1} of the w x h grey picture, or null to keep it all. */
    static int[] keep(int[] gray, int w, int h, int threshold, boolean darkInk) {
        int[] colInk = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (ink(gray[y * w + x], threshold, darkInk)) {
                    colInk[x]++;
                }
            }
        }
        boolean[] ruleCol = new boolean[w];
        int liveW = 0;
        for (int x = 0; x < w; x++) {
            if (colInk[x] >= h * 0.85) {
                ruleCol[x] = true;
            } else {
                liveW++;
            }
        }
        // The rows of ink, border columns left out: a band touching the top or the bottom that is much lower than
        // the line's is the tip of the line above or below.
        int blank = Math.max(1, (int) Math.floor(w * 0.02));
        java.util.List<int[]> bands = new java.util.ArrayList<>();
        int start = -1;
        for (int y = 0; y <= h; y++) {
            int n = 0;
            if (y < h) {
                for (int x = 0; x < w; x++) {
                    if (!ruleCol[x] && ink(gray[y * w + x], threshold, darkInk)) {
                        n++;
                    }
                }
            }
            boolean on = y < h && n > blank && n < liveW * 0.85;
            if (on && start < 0) {
                start = y;
            } else if (!on && start >= 0) {
                bands.add(new int[] {start, y});
                start = -1;
            }
        }
        int y0 = 0;
        int y1 = h;
        if (bands.size() >= 2 && h >= 24) {
            int mh = 0;
            for (int[] b : bands) {
                mh = Math.max(mh, b[1] - b[0]);
            }
            int[] top = bands.get(0);
            int[] next = bands.get(1);
            int[] bot = bands.get(bands.size() - 1);
            int[] prev = bands.get(bands.size() - 2);
            if (top[0] <= 1 && top[1] - top[0] < mh * 0.45) {
                y0 = (top[1] + next[0]) >> 1;
            }
            if (bot[1] >= h - 1 && bot[1] - bot[0] < mh * 0.45) {
                y1 = (bot[0] + prev[1] + 1) >> 1;
            }
            // What is left must be a line of letters: at least two runs of ink columns a letter wide.
            if (y0 > 0 || y1 < h) {
                int runs = 0;
                int len = 0;
                for (int x = 0; x <= w; x++) {
                    boolean on = false;
                    if (x < w && !ruleCol[x]) {
                        for (int y = y0; y < y1 && !on; y++) {
                            on = ink(gray[y * w + x], threshold, darkInk);
                        }
                    }
                    if (on) {
                        len++;
                    } else {
                        if (len >= (y1 - y0) * 0.3) {
                            runs++;
                        }
                        len = 0;
                    }
                }
                if (runs < 2) {
                    y0 = 0;
                    y1 = h;
                }
            }
        }
        // A border at the left or right end: a column through nearly the whole height (or dotted from top to
        // bottom), nothing of the line beyond it, and a clear gap before the letters start. A horizontal rule along
        // the top or bottom does not count as letters.
        boolean[] ruleRow = new boolean[h];
        for (int y = 0; y < h; y++) {
            int n = 0;
            for (int x = 0; x < w; x++) {
                if (ink(gray[y * w + x], threshold, darkInk)) {
                    n++;
                }
            }
            ruleRow[y] = n >= w * 0.85;
        }
        final int fy0 = y0;
        final int fy1 = y1;
        java.util.function.IntPredicate textCol = x -> {
            for (int y = fy0; y < fy1; y++) {
                if (!ruleRow[y] && ink(gray[y * w + x], threshold, darkInk)) {
                    return true;
                }
            }
            return false;
        };
        java.util.function.IntPredicate dotted = x -> {
            if (colInk[x] < h * 0.35) {
                return false;
            }
            int first = -1;
            int last = -1;
            int run = 0;
            int maxRun = 0;
            for (int y = 0; y < h; y++) {
                if (ink(gray[y * w + x], threshold, darkInk)) {
                    if (first < 0) {
                        first = y;
                    } else {
                        maxRun = Math.max(maxRun, run);
                    }
                    last = y;
                    run = 0;
                } else {
                    run++;
                }
            }
            return first <= h * 0.1 && last >= h * 0.9 && maxRun <= h * 0.15;
        };
        java.util.function.IntPredicate border = x -> colInk[x] >= h * 0.95 || dotted.test(x);
        int gap = Math.max(2, Math.round(h * 0.1f));
        int x0 = 0;
        int x1 = w;
        for (int x = 0; x < w * 0.2; x++) {
            if (border.test(x)) {
                int e = runEnd(x, 1, w, gap, border, textCol);
                if (clearFrom(e + 1, 1, w, gap, textCol)) {
                    x0 = e + 1;
                }
                break;
            }
            if (textCol.test(x)) {
                int s = solidAt(x + 1, (int) Math.floor(w * 0.2), 1, colInk, h);
                if (s >= 0) {
                    int e = runEnd(s, 1, w, gap, border, textCol);
                    if (clearFrom(e + 1, 1, w, gap, textCol)) {
                        x0 = e + 1;
                    }
                }
                break;
            }
        }
        for (int x = w - 1; x >= w * 0.8; x--) {
            if (border.test(x)) {
                int e = runEnd(x, -1, w, gap, border, textCol);
                if (clearFrom(e - 1, -1, w, gap, textCol)) {
                    x1 = e;
                }
                break;
            }
            if (textCol.test(x)) {
                int s = solidAt(x - 1, (int) Math.ceil(w * 0.8), -1, colInk, h);
                if (s >= 0) {
                    int e = runEnd(s, -1, w, gap, border, textCol);
                    if (clearFrom(e - 1, -1, w, gap, textCol)) {
                        x1 = e;
                    }
                }
                break;
            }
        }
        return y0 > 0 || y1 < h || x0 > 0 || x1 < w ? new int[] {x0, x1, y0, y1} : null;
    }

    private static boolean ink(int v, int threshold, boolean darkInk) {
        return darkInk ? v <= threshold : v > threshold;
    }

    /** No letters for {@code gap} columns from x on, going dir. */
    private static boolean clearFrom(int x, int dir, int w, int gap, java.util.function.IntPredicate textCol) {
        for (int k = 0; k < gap; k++) {
            int c = x + dir * k;
            if (c < 0 || c >= w || textCol.test(c)) {
                return false;
            }
        }
        return true;
    }

    /** The far side of a border at x, going dir: a second border right inside it (a double one) is the same one. */
    private static int runEnd(int x, int dir, int w, int gap, java.util.function.IntPredicate border,
            java.util.function.IntPredicate textCol) {
        int e = x;
        for (;;) {
            while (e + dir >= 0 && e + dir < w && border.test(e + dir)) {
                e += dir;
            }
            int k = 1;
            while (k <= gap && e + dir * k >= 0 && e + dir * k < w && !border.test(e + dir * k)
                    && !textCol.test(e + dir * k)) {
                k++;
            }
            if (k <= gap && e + dir * k >= 0 && e + dir * k < w && border.test(e + dir * k)) {
                e += dir * k;
            } else {
                return e;
            }
        }
    }

    /** The first solid column (ink through the whole height) from {@code from} toward {@code to}; -1 if none. */
    private static int solidAt(int from, int to, int dir, int[] colInk, int h) {
        for (int x = from; dir > 0 ? x < to : x >= to; x += dir) {
            if (x >= 0 && x < colInk.length && colInk[x] >= h * 0.95) {
                return x;
            }
        }
        return -1;
    }

    private static int otsu(int[] hist, int total) {
        long sumAll = 0;
        for (int v = 0; v < 256; v++) {
            sumAll += (long) v * hist[v];
        }
        long wB = 0;
        long sumB = 0;
        double best = -1;
        int threshold = 127;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) {
                continue;
            }
            long wF = total - wB;
            if (wF == 0) {
                break;
            }
            sumB += (long) t * hist[t];
            double mB = (double) sumB / wB;
            double mF = (double) (sumAll - sumB) / wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > best) {
                best = between;
                threshold = t;
            }
        }
        return threshold;
    }

    private static int median(int[] hist, int total) {
        long seen = 0;
        for (int v = 0; v < 256; v++) {
            seen += hist[v];
            if (seen * 2 >= total) {
                return v;
            }
        }
        return 255;
    }
}
