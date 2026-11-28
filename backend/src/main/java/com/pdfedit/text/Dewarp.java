package com.pdfedit.text;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * A page photographed at a slant (a phone held over a sheet): its lines slope differently at the top and at the
 * bottom, so turning the whole page sets none of them straight - over the page the slopes even out, and the page
 * was measured straight. The page is cut into bands across, the slope of each measured as {@link Deskew} measures a
 * page's; when they differ enough, and change one way only from top to bottom, every row is shifted up or down by
 * (x - middle) x tan(slope at that row), which lays the lines flat. A page also turned as a whole (1.2 degrees and
 * more) is turned first and what slant is left laid flat after. Used only when it sets the lines clearly straighter
 * than turning the page would. Follows kordoc's ocr/deskew.ts (dewarp, 4.25.19 - 4.25.26; MIT, Copyright (c) 2026
 * chrisryugj).
 */
final class Dewarp {

    private static final double MAX_DEGREES = 5;
    private static final double MIN_APPLY = 0.25;
    private static final double MIN_GAIN = 1.03;
    private static final int MIN_LINES = 3;
    private static final double MIN_GAIN_FEW = 1.05;
    private static final double LINE_BIN_SHARE = 0.05;
    private static final int TARGET_LONG = 1000;
    /** Bands across the page (overlapping by half), and how clear and how full of lines a band must be to count. */
    private static final int BANDS = 6;
    private static final double BAND_MIN_GAIN = 1.05;
    private static final int BAND_MIN_LINES = 3;
    /** The bands' slopes differ at least this much (degrees). */
    private static final double SPREAD = 0.8;
    /** A slope going back by more than this (a stamp's or a big title's band) is not a slant photographed. */
    private static final double ONE_WAY_TOL = 0.3;
    /** Laid flat, the lines must be this much sharper than with the page only turned. */
    private static final double WIN = 1.02;
    private static final double LINEAR_TOL = 0.8;
    private static final double LINEAR_WIN = 1.12;
    /** A page turned at least this much (degrees) is turned before what is left is laid flat. */
    private static final double TURN_FIRST = 1.2;

    private Dewarp() {
    }

    /** The slope at each row: knots {row, degrees} between which it runs straight, beyond them as at the end ones. */
    record Field(double cx, double[][] knots) {
        double degrees(double y) {
            if (y <= knots[0][0]) {
                return knots[0][1];
            }
            for (int i = 1; i < knots.length; i++) {
                if (y <= knots[i][0]) {
                    double t = (y - knots[i - 1][0]) / (knots[i][0] - knots[i - 1][0]);
                    return knots[i - 1][1] * (1 - t) + knots[i][1] * t;
                }
            }
            return knots[knots.length - 1][1];
        }
    }

    /** A slope measured: degrees the lines lean (clockwise positive), how clear, how many lines, the sharpness. */
    record Estimate(double angle, double gain, int lines, double score) {
    }

    /** How to lay a page flat: turned clockwise by {@code turn} degrees first (0 for none), then rows shifted by the field. */
    record Plan(double turn, Field field) {
        /** The same plan for the page drawn {@code scale} times as big. */
        Plan scaled(double scale, int width) {
            double[][] knots = new double[field.knots().length][];
            for (int i = 0; i < knots.length; i++) {
                knots[i] = new double[] {field.knots()[i][0] * scale, field.knots()[i][1]};
            }
            return new Plan(turn, new Field((width - 1) / 2.0, knots));
        }
    }

    /** How to lay the page flat, or null when it needs no laying flat (straight, or only turned as a whole). */
    static Plan plan(BufferedImage page) {
        int w = page.getWidth();
        int h = page.getHeight();
        int[] grey = grey(page);
        int step = step(w, h);
        Estimate est = estimate(grey, w, 0, h, step);
        Pick direct = pick(grey, w, h, step);
        boolean turns = Math.abs(est.angle()) >= MIN_APPLY && est.gain() >= (est.lines() < MIN_LINES ? MIN_GAIN_FEW : MIN_GAIN);
        Pick after = turns && Math.abs(est.angle()) >= TURN_FIRST ? pick(grey(Deskew.turned(page, -est.angle())), w, h, step) : null;
        if (direct != null && direct.score() > est.score() * WIN && (after == null || direct.score() >= after.score())) {
            return new Plan(0, direct.field());
        }
        if (after != null && after.score() > est.score() * WIN) {
            return new Plan(-est.angle(), after.field());
        }
        return null;
    }

    /** The page laid flat as planned. */
    static BufferedImage apply(BufferedImage page, Plan plan) {
        return rows(plan.turn() == 0 ? page : Deskew.turned(page, plan.turn()), plan.field());
    }

    /** The page laid flat (turned first when it had to be), or null when it needs no laying flat. */
    static BufferedImage flattened(BufferedImage page) {
        Plan plan = plan(page);
        return plan == null ? null : apply(page, plan);
    }

    private record Pick(Field field, double score) {
    }

    /** The slopes of the page's bands, laid as straight lines between them or as one straight line top to bottom. */
    private static Pick pick(int[] grey, int w, int h, int step) {
        Field piecewise = field(grey, w, h, step);
        if (piecewise == null) {
            return null;
        }
        Field linear = linear(piecewise, h);
        double piecewiseScore = flatScore(grey, w, h, piecewise, step);
        double linearScore = linear == null ? 0 : flatScore(grey, w, h, linear, step);
        return linear != null && linearScore > piecewiseScore * LINEAR_WIN
                ? new Pick(linear, linearScore) : new Pick(piecewise, piecewiseScore);
    }

    static Field field(int[] grey, int w, int h, int step) {
        int bh = (int) Math.floor(h / (BANDS / 2.0 + 0.5));
        if (bh < 50) {
            return null;
        }
        List<double[]> knots = new ArrayList<>();
        for (int k = 0; k < BANDS; k++) {
            int y0 = (int) Math.floor((double) k * (h - bh) / (BANDS - 1));
            Estimate e = estimate(grey, w, y0, y0 + bh, step);
            if (e.gain() >= BAND_MIN_GAIN && e.lines() >= BAND_MIN_LINES) {
                knots.add(new double[] {y0 + bh / 2.0, e.angle()});
            }
        }
        if (knots.size() < 2) {
            return null;
        }
        double most = knots.stream().mapToDouble(k -> k[1]).max().orElse(0);
        double least = knots.stream().mapToDouble(k -> k[1]).min().orElse(0);
        if (most - least < SPREAD) {
            return null;
        }
        boolean up = false;
        boolean down = false;
        for (int i = 1; i < knots.size(); i++) {
            double change = knots.get(i)[1] - knots.get(i - 1)[1];
            up |= change > ONE_WAY_TOL;
            down |= -change > ONE_WAY_TOL;
        }
        if (up && down) {
            return null;
        }
        return new Field((w - 1) / 2.0, knots.toArray(new double[0][]));
    }

    /** One straight line through the bands' slopes, top to bottom, when they lie on one and cross 0 (a trapezium). */
    static Field linear(Field f, int h) {
        double[][] k = f.knots();
        if (k.length < 3) {
            return null;
        }
        double my = 0;
        double ma = 0;
        for (double[] p : k) {
            my += p[0] / k.length;
            ma += p[1] / k.length;
        }
        double num = 0;
        double den = 0;
        for (double[] p : k) {
            num += (p[0] - my) * (p[1] - ma);
            den += (p[0] - my) * (p[0] - my);
        }
        double b = num / den;
        double least = Double.MAX_VALUE;
        double most = -Double.MAX_VALUE;
        for (double[] p : k) {
            if (Math.abs(p[1] - (ma + b * (p[0] - my))) > LINEAR_TOL) {
                return null;
            }
            least = Math.min(least, p[1]);
            most = Math.max(most, p[1]);
        }
        if (least >= 0 || most <= 0) {
            return null;
        }
        return new Field(f.cx(), new double[][] {{0, ma + b * (0 - my)}, {h - 1, ma + b * (h - 1 - my)}});
    }

    /** Each row shifted by (x - cx) tan(slope): the picture laid flat (paper where nothing comes from). */
    static BufferedImage rows(BufferedImage page, Field f) {
        int w = page.getWidth();
        int h = page.getHeight();
        int[] in = page.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            double t = Math.tan(Math.toRadians(f.degrees(y)));
            for (int x = 0; x < w; x++) {
                double sy = y + (x - f.cx()) * t;
                int y0 = (int) Math.floor(sy);
                if (y0 < 0 || y0 >= h - 1) {
                    out[y * w + x] = 0xffffffff;
                    continue;
                }
                double wy = sy - y0;
                int a = in[y0 * w + x];
                int b = in[(y0 + 1) * w + x];
                int r = (int) Math.round(((a >> 16) & 0xff) * (1 - wy) + ((b >> 16) & 0xff) * wy);
                int g = (int) Math.round(((a >> 8) & 0xff) * (1 - wy) + ((b >> 8) & 0xff) * wy);
                int bl = (int) Math.round((a & 0xff) * (1 - wy) + (b & 0xff) * wy);
                out[y * w + x] = 0xff000000 | (r << 16) | (g << 8) | bl;
            }
        }
        BufferedImage flat = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        flat.setRGB(0, 0, w, h, out, 0, w);
        return flat;
    }

    static int[] grey(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] rgb = image.getRGB(0, 0, w, h, null, 0, w);
        int[] grey = new int[w * h];
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            grey[i] = (((p >> 16) & 0xff) * 77 + ((p >> 8) & 0xff) * 150 + (p & 0xff) * 29) >> 8;
        }
        return grey;
    }

    static int step(int w, int h) {
        return Math.max(1, (int) Math.ceil(Math.max(w, h) / (double) TARGET_LONG));
    }

    /** The slope of the rows from y0 to y1, measured on ink points every {@code step} pixels (kordoc's estimateSkew). */
    static Estimate estimate(int[] grey, int w, int y0, int y1, int step) {
        int sw = w / step;
        int sh = (y1 - y0) / step;
        List<int[]> points = new ArrayList<>();
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                if (grey[(y0 + y * step) * w + x * step] < 128) {
                    points.add(new int[] {x, y});
                }
            }
        }
        if (points.size() < 200) {
            return new Estimate(0, 1, 0, 0);
        }
        int diag = (int) Math.ceil(Math.hypot(sw, sh));
        double[] hist = new double[2 * diag + 2];
        double s0 = score(points, hist, diag, 0);
        double best = 0;
        double bestScore = s0;
        for (double d = -MAX_DEGREES; d <= MAX_DEGREES + 1e-9; d += 0.2) {
            double v = score(points, hist, diag, d);
            if (v > bestScore) {
                bestScore = v;
                best = d;
            }
        }
        double coarse = best;
        for (double d = coarse - 0.2; d <= coarse + 0.2 + 1e-9; d += 0.02) {
            double v = score(points, hist, diag, d);
            if (v > bestScore) {
                bestScore = v;
                best = d;
            }
        }
        score(points, hist, diag, best);
        double max = 0;
        for (double v : hist) {
            max = Math.max(max, v);
        }
        int lines = 0;
        boolean on = false;
        for (double v : hist) {
            boolean ink = v > max * LINE_BIN_SHARE;
            if (ink && !on) {
                lines++;
            }
            on = ink;
        }
        return new Estimate(Math.round(-best * 100) / 100.0, s0 > 0 ? bestScore / s0 : 1, lines, bestScore);
    }

    private static double score(List<int[]> points, double[] hist, int diag, double degrees) {
        double t = Math.toRadians(degrees);
        double c = Math.cos(t);
        double s = Math.sin(t);
        java.util.Arrays.fill(hist, 0);
        for (int[] p : points) {
            hist[(int) Math.round(p[1] * c + p[0] * s) + diag]++;
        }
        double sum = 0;
        for (double v : hist) {
            sum += v * v;
        }
        return sum;
    }

    /** How sharp the rows' ink is with the page laid flat by the field (kordoc's dewarpScore). */
    static double flatScore(int[] grey, int w, int h, Field f, int step) {
        int sh = h / step;
        int pad = (int) Math.ceil(sh / 2.0);
        double[] hist = new double[sh + 2 * pad + 2];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < w / step; x++) {
                if (grey[y * step * w + x * step] < 128) {
                    double px = x * step;
                    double py = y * step;
                    int r = (int) Math.round((py - (px - f.cx()) * Math.tan(Math.toRadians(f.degrees(py)))) / step) + pad;
                    if (r >= 0 && r < hist.length) {
                        hist[r]++;
                    }
                }
            }
        }
        double sum = 0;
        for (double v : hist) {
            sum += v * v;
        }
        return sum;
    }
}
