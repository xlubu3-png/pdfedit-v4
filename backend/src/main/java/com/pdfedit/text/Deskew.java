package com.pdfedit.text;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * How far a scanned page is turned, and the page turned straight. A sheet fed a little askew (0.5 - 3 degrees) has
 * its table borders running across rows of pixels, and lines followed along rows break apart.
 * <p>
 * The angle is the one at which the ink, counted row by row along that slant, falls into the sharpest peaks
 * (the lines of text and the borders lying flat): tried every 0.2 degrees up to 5 either way, then every 0.02
 * around the best. The approach and its limits follow kordoc's ocr/deskew.ts (MIT, Copyright (c) 2026
 * chrisryugj).
 */
final class Deskew {

    /** Turns larger than this are not a sheet fed askew (a page on its side is something else). */
    static final double MAX_DEGREES = 5;
    /** Smaller turns are left alone: turning the picture blurs it more than it helps. */
    static final double MIN_APPLY = 0.25;
    /** The best angle must give peaks this much sharper than none: a page with little text has chance peaks. */
    static final double MIN_GAIN = 1.03;
    /** The page is looked at with its long side about this many pixels. */
    private static final int TARGET_LONG = 1000;
    /** Darker than this (0 - 255) is ink for the count. */
    private static final int INK = 160;
    /** Fewer ink points than this is too little to tell a slant from. */
    private static final int MIN_POINTS = 400;
    /**
     * Fewer lines of text than this (bands of ink, set straight) is too little too: in a picture of one or two lines
     * the shape of a font's strokes (a Windows gothic) reads as a slant of half a degree or so, and the picture was
     * turned for nothing (kordoc 4.25.10).
     */
    static final int MIN_LINES = 3;
    /** A row with less ink than this share of the fullest row is a gap between lines. */
    private static final double BAND_SHARE = 0.15;

    private Deskew() {
    }

    /**
     * Degrees to turn the page clockwise (as the screen shows it, y down) to set it straight; 0 when it is straight
     * enough or the slant cannot be told.
     */
    static double angle(BufferedImage image) {
        int step = Math.max(1, Math.max(image.getWidth(), image.getHeight()) / TARGET_LONG);
        int w = image.getWidth() / step;
        int h = image.getHeight() / step;
        int[] row = new int[image.getWidth()];
        double[] xs = new double[w * h];
        double[] ys = new double[w * h];
        int n = 0;
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y * step, image.getWidth(), 1, row, 0, image.getWidth());
            for (int x = 0; x < w; x++) {
                int p = row[x * step];
                int grey = (((p >> 16) & 0xff) * 299 + ((p >> 8) & 0xff) * 587 + (p & 0xff) * 114) / 1000;
                if (grey < INK) {
                    xs[n] = x - w / 2.0;
                    ys[n] = y - h / 2.0;
                    n++;
                }
            }
        }
        if (n < MIN_POINTS) {
            return 0;
        }
        int bins = (int) Math.ceil(Math.hypot(w, h)) + 4;
        double none = sharpness(xs, ys, n, 0, bins);
        double best = 0;
        double bestScore = none;
        for (double a = -MAX_DEGREES; a <= MAX_DEGREES + 1e-9; a += 0.2) {
            double score = sharpness(xs, ys, n, a, bins);
            if (score > bestScore) {
                bestScore = score;
                best = a;
            }
        }
        double coarse = best;
        for (double a = coarse - 0.2; a <= coarse + 0.2 + 1e-9; a += 0.02) {
            double score = sharpness(xs, ys, n, a, bins);
            if (score > bestScore) {
                bestScore = score;
                best = a;
            }
        }
        if (Math.abs(best) < MIN_APPLY || bestScore < none * MIN_GAIN || lines(xs, ys, n, best, bins) < MIN_LINES) {
            return 0;
        }
        return Math.round(best * 100) / 100.0;
    }

    /** How many lines of text (bands of rows with ink, gaps between) the page has, turned by {@code degrees}. */
    static int lines(double[] xs, double[] ys, int n, double degrees, int bins) {
        int[] counts = rows(xs, ys, n, degrees, bins);
        int most = 0;
        for (int c : counts) {
            most = Math.max(most, c);
        }
        double inked = Math.max(1, most * BAND_SHARE);
        int bands = 0;
        boolean in = false;
        for (int c : counts) {
            if (c >= inked && !in) {
                bands++;
            }
            in = c >= inked;
        }
        return bands;
    }

    private static int[] rows(double[] xs, double[] ys, int n, double degrees, int bins) {
        double r = Math.toRadians(degrees);
        double sin = Math.sin(r);
        double cos = Math.cos(r);
        int[] counts = new int[bins];
        int half = bins / 2;
        for (int i = 0; i < n; i++) {
            int y = (int) Math.round(xs[i] * sin + ys[i] * cos) + half;
            if (y >= 0 && y < bins) {
                counts[y]++;
            }
        }
        return counts;
    }

    /** How peaked the rows' ink counts are with the page turned by {@code degrees}: the sum of their squares. */
    private static double sharpness(double[] xs, double[] ys, int n, double degrees, int bins) {
        int[] counts = rows(xs, ys, n, degrees, bins);
        double sum = 0;
        for (int c : counts) {
            sum += (double) c * c;
        }
        return sum;
    }

    /** The picture turned clockwise by {@code degrees} about its middle, on white. */
    static BufferedImage turned(BufferedImage image, double degrees) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, AffineTransform.getRotateInstance(Math.toRadians(degrees), image.getWidth() / 2.0,
                image.getHeight() / 2.0), null);
        g.dispose();
        return out;
    }
}
