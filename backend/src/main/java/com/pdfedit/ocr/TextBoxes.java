package com.pdfedit.ocr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the detection model's text probability map into line boxes: the "DB" post-processing of PaddleOCR,
 * as RapidOCR does it (threshold, small dilation, a minimum-area rectangle per blob, its mean probability,
 * then the rectangle grown by area x 1.6 / perimeter on every side).
 */
final class TextBoxes {

    private static final float THRESH = 0.3f;
    private static final float BOX_THRESH = 0.5f;
    private static final double UNCLIP_RATIO = 1.6;
    private static final int MIN_SIZE = 3;
    private static final int MAX_CANDIDATES = 1000;
    /** Boxes whose tops are closer than this (pixels) are taken as one line when sorting. */
    private static final int SAME_LINE = 10;

    private TextBoxes() {
    }

    /**
     * @param prob   probabilities, row by row, {@code width x height}
     * @param destW  width of the picture the boxes are for (the map can be a resized copy)
     * @return boxes as 8 numbers (tl, tr, br, bl), top to bottom and left to right
     */
    static List<float[]> find(float[] prob, int width, int height, int destW, int destH) {
        boolean[] fg = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // A 2x2 dilation: the pixel or its left, upper or upper-left neighbour is text.
                int i = y * width + x;
                fg[i] = prob[i] > THRESH
                        || x > 0 && prob[i - 1] > THRESH
                        || y > 0 && prob[i - width] > THRESH
                        || x > 0 && y > 0 && prob[i - width - 1] > THRESH;
            }
        }
        List<float[]> boxes = new ArrayList<>();
        boolean[] seen = new boolean[fg.length];
        int[] queue = new int[fg.length];
        int candidates = 0;
        for (int start = 0; start < fg.length && candidates < MAX_CANDIDATES; start++) {
            if (!fg[start] || seen[start]) {
                continue;
            }
            candidates++;
            int end = blob(fg, seen, queue, start, width, height);
            Rect rect = Rect.around(rowEnds(queue, end, width));
            if (rect == null || rect.shortSide() < MIN_SIZE) {
                continue;
            }
            double[][] corners = rect.ordered();
            float score = meanInside(prob, width, height, corners);
            if (score < BOX_THRESH) {
                continue;
            }
            double grow = rect.w * rect.h * UNCLIP_RATIO / (2 * (rect.w + rect.h));
            Rect grown = rect.grownBy(grow);
            if (grown.shortSide() < MIN_SIZE + 2) {
                continue;
            }
            float[] box = new float[8];
            double[][] g = grown.ordered();
            for (int k = 0; k < 4; k++) {
                box[2 * k] = (float) Math.min(Math.max(Math.rint(g[k][0] / width * destW), 0), destW);
                box[2 * k + 1] = (float) Math.min(Math.max(Math.rint(g[k][1] / height * destH), 0), destH);
            }
            float[] clockwise = clockwise(box, destW, destH);
            if (clockwise != null) {
                boxes.add(clockwise);
            }
        }
        return sorted(boxes);
    }

    /** Collects the 8-connected blob at {@code start} into {@code queue[0..end)} and returns {@code end}. */
    private static int blob(boolean[] fg, boolean[] seen, int[] queue, int start, int width, int height) {
        int head = 0;
        int tail = 0;
        queue[tail++] = start;
        seen[start] = true;
        while (head < tail) {
            int i = queue[head++];
            int x = i % width;
            int y = i / width;
            for (int dy = -1; dy <= 1; dy++) {
                int ny = y + dy;
                if (ny < 0 || ny >= height) {
                    continue;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx;
                    if (nx < 0 || nx >= width || (dx == 0 && dy == 0)) {
                        continue;
                    }
                    int n = ny * width + nx;
                    if (fg[n] && !seen[n]) {
                        seen[n] = true;
                        queue[tail++] = n;
                    }
                }
            }
        }
        return tail;
    }

    /** The leftmost and rightmost pixel of every row of the blob: enough for its convex hull. */
    private static long[] rowEnds(int[] queue, int end, int width) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int k = 0; k < end; k++) {
            int y = queue[k] / width;
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        int rows = maxY - minY + 1;
        int[] left = new int[rows];
        int[] right = new int[rows];
        Arrays.fill(left, Integer.MAX_VALUE);
        Arrays.fill(right, Integer.MIN_VALUE);
        for (int k = 0; k < end; k++) {
            int x = queue[k] % width;
            int r = queue[k] / width - minY;
            left[r] = Math.min(left[r], x);
            right[r] = Math.max(right[r], x);
        }
        long[] points = new long[rows * 2];
        for (int r = 0; r < rows; r++) {
            points[2 * r] = pack(left[r], minY + r);
            points[2 * r + 1] = pack(right[r], minY + r);
        }
        return points;
    }

    private static long pack(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    private static int px(long p) {
        return (int) (p >> 32);
    }

    private static int py(long p) {
        return (int) p;
    }

    /** The mean probability inside the box (its corners truncated to whole pixels, edges included). */
    private static float meanInside(float[] prob, int width, int height, double[][] corners) {
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        int[][] poly = new int[4][2];
        for (int k = 0; k < 4; k++) {
            minX = Math.min(minX, corners[k][0]);
            maxX = Math.max(maxX, corners[k][0]);
            minY = Math.min(minY, corners[k][1]);
            maxY = Math.max(maxY, corners[k][1]);
            poly[k][0] = (int) corners[k][0];
            poly[k][1] = (int) corners[k][1];
        }
        int x0 = clamp((int) Math.floor(minX), width - 1);
        int x1 = clamp((int) Math.ceil(maxX), width - 1);
        int y0 = clamp((int) Math.floor(minY), height - 1);
        int y1 = clamp((int) Math.ceil(maxY), height - 1);
        double sum = 0;
        int count = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (inside(poly, x, y)) {
                    sum += prob[y * width + x];
                    count++;
                }
            }
        }
        return count == 0 ? 0 : (float) (sum / count);
    }

    /** Inside a convex polygon, or within half a pixel of its edge (as a filled polygon's outline is). */
    private static boolean inside(int[][] poly, int x, int y) {
        int sign = 0;
        for (int k = 0; k < poly.length; k++) {
            int[] a = poly[k];
            int[] b = poly[(k + 1) % poly.length];
            double ex = b[0] - a[0];
            double ey = b[1] - a[1];
            double length = Math.hypot(ex, ey);
            if (length == 0) {
                continue;
            }
            double side = (ex * (y - a[1]) - ey * (x - a[0])) / length;
            if (Math.abs(side) <= 0.5) {
                continue;
            }
            int s = side > 0 ? 1 : -1;
            if (sign == 0) {
                sign = s;
            } else if (s != sign) {
                return false;
            }
        }
        return true;
    }

    private static int clamp(int v, int max) {
        return Math.min(Math.max(v, 0), max);
    }

    /** Corners as top-left, top-right, bottom-right, bottom-left, whole pixels inside the picture; null if tiny. */
    private static float[] clockwise(float[] box, int width, int height) {
        Integer[] byX = {0, 1, 2, 3};
        Arrays.sort(byX, Comparator.comparingDouble(k -> box[2 * k]));
        int a = byX[0];
        int b = byX[1];
        int tl = box[2 * a + 1] <= box[2 * b + 1] ? a : b;
        int bl = tl == a ? b : a;
        int c = byX[2];
        int d = byX[3];
        int tr = box[2 * c + 1] <= box[2 * d + 1] ? c : d;
        int br = tr == c ? d : c;
        float[] out = new float[8];
        int[] order = {tl, tr, br, bl};
        for (int k = 0; k < 4; k++) {
            out[2 * k] = (int) Math.min(Math.max(box[2 * order[k]], 0), width - 1);
            out[2 * k + 1] = (int) Math.min(Math.max(box[2 * order[k] + 1], 0), height - 1);
        }
        int w = (int) Math.hypot(out[0] - out[2], out[1] - out[3]);
        int h = (int) Math.hypot(out[0] - out[6], out[1] - out[7]);
        return w <= 3 || h <= 3 ? null : out;
    }

    /** Top to bottom; boxes whose tops are within a few pixels count as one line, read left to right. */
    private static List<float[]> sorted(List<float[]> boxes) {
        List<float[]> byTop = new ArrayList<>(boxes);
        byTop.sort(Comparator.comparingDouble(b -> b[1]));
        int[] line = new int[byTop.size()];
        for (int k = 1; k < byTop.size(); k++) {
            line[k] = line[k - 1] + (byTop.get(k)[1] - byTop.get(k - 1)[1] >= SAME_LINE ? 1 : 0);
        }
        Integer[] order = new Integer[byTop.size()];
        for (int k = 0; k < order.length; k++) {
            order[k] = k;
        }
        Arrays.sort(order, Comparator.<Integer>comparingInt(k -> line[k]).thenComparingDouble(k -> byTop.get(k)[0]));
        List<float[]> out = new ArrayList<>(order.length);
        for (int k : order) {
            out.add(byTop.get(k));
        }
        return out;
    }

    /** A rectangle at any angle: centre, the unit vector along its width, and its two sides. */
    static final class Rect {
        final double cx;
        final double cy;
        final double ux;
        final double uy;
        final double w;
        final double h;

        private Rect(double cx, double cy, double ux, double uy, double w, double h) {
            this.cx = cx;
            this.cy = cy;
            this.ux = ux;
            this.uy = uy;
            this.w = w;
            this.h = h;
        }

        double shortSide() {
            return Math.min(w, h);
        }

        Rect grownBy(double d) {
            return new Rect(cx, cy, ux, uy, w + 2 * d, h + 2 * d);
        }

        /** The smallest rectangle around the points (rotating calipers over their convex hull). */
        static Rect around(long[] points) {
            long[] hull = hull(points);
            if (hull.length == 0) {
                return null;
            }
            if (hull.length == 1) {
                return new Rect(px(hull[0]), py(hull[0]), 1, 0, 0, 0);
            }
            Rect best = null;
            double bestArea = Double.MAX_VALUE;
            for (int k = 0; k < hull.length; k++) {
                long a = hull[k];
                long b = hull[(k + 1) % hull.length];
                double ex = px(b) - px(a);
                double ey = py(b) - py(a);
                double length = Math.hypot(ex, ey);
                if (length == 0) {
                    continue;
                }
                ex /= length;
                ey /= length;
                double minU = Double.MAX_VALUE;
                double maxU = -Double.MAX_VALUE;
                double minV = Double.MAX_VALUE;
                double maxV = -Double.MAX_VALUE;
                for (long p : hull) {
                    double u = px(p) * ex + py(p) * ey;
                    double v = -px(p) * ey + py(p) * ex;
                    minU = Math.min(minU, u);
                    maxU = Math.max(maxU, u);
                    minV = Math.min(minV, v);
                    maxV = Math.max(maxV, v);
                }
                double area = (maxU - minU) * (maxV - minV);
                if (area < bestArea) {
                    bestArea = area;
                    double cu = (minU + maxU) / 2;
                    double cv = (minV + maxV) / 2;
                    best = new Rect(cu * ex - cv * ey, cu * ey + cv * ex, ex, ey, maxU - minU, maxV - minV);
                }
            }
            return best;
        }

        /**
         * The corners in the order PaddleOCR's {@code get_mini_boxes} gives them: sorted by x, then the left pair
         * and the right pair each split by y.
         */
        double[][] ordered() {
            double vx = -uy;
            double vy = ux;
            double[][] p = new double[4][];
            int k = 0;
            for (int su : new int[] {-1, 1}) {
                for (int sv : new int[] {-1, 1}) {
                    p[k++] = new double[] {cx + su * ux * w / 2 + sv * vx * h / 2, cy + su * uy * w / 2 + sv * vy * h / 2};
                }
            }
            Arrays.sort(p, Comparator.comparingDouble(q -> q[0]));
            double[] first = p[1][1] > p[0][1] ? p[0] : p[1];
            double[] fourth = p[1][1] > p[0][1] ? p[1] : p[0];
            double[] second = p[3][1] > p[2][1] ? p[2] : p[3];
            double[] third = p[3][1] > p[2][1] ? p[3] : p[2];
            return new double[][] {first, second, third, fourth};
        }

        /** Andrew's monotone chain; collinear points are dropped. */
        private static long[] hull(long[] points) {
            // pack() puts x in the high bits, so sorting the longs sorts by x, then y (never negative here).
            long[] p = Arrays.stream(points).distinct().sorted().toArray();
            if (p.length < 3) {
                return p;
            }
            long[] h = new long[2 * p.length];
            int n = 0;
            for (long q : p) {
                while (n >= 2 && cross(h[n - 2], h[n - 1], q) <= 0) {
                    n--;
                }
                h[n++] = q;
            }
            for (int i = p.length - 2, lower = n + 1; i >= 0; i--) {
                while (n >= lower && cross(h[n - 2], h[n - 1], p[i]) <= 0) {
                    n--;
                }
                h[n++] = p[i];
            }
            return Arrays.copyOf(h, n - 1);
        }

        private static long cross(long o, long a, long b) {
            return (long) (px(a) - px(o)) * (py(b) - py(o)) - (long) (py(a) - py(o)) * (px(b) - px(o));
        }
    }
}
