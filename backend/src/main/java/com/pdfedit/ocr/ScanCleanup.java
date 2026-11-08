package com.pdfedit.ocr;

import java.awt.image.BufferedImage;

/**
 * A faint or speckled scan made easier to read, for the reader only (the page keeps its own picture): a fax, a copy
 * of a copy, a light pencil form. Two things, each done only when the picture needs it:
 * <ul>
 * <li>Faint ink - its darkest letters still light grey - is darkened: the picture's darkest and lightest are spread to
 * black and white (the paper's own tint goes white too).</li>
 * <li>Specks - lone dark dots all over, as a dusty glass or a fax leaves - are taken out: a dot no bigger than a
 * pixel or two with paper all round. Letters' strokes are never that small at the resolution pages are read.</li>
 * </ul>
 */
public final class ScanCleanup {

    /** Ink this light or lighter (the darkest 1 % of the picture) is faint. */
    static final int FAINT_INK = 110;
    /** Paper this dark or darker (the lightest 5 %) is tinted. */
    static final int TINTED_PAPER = 225;
    /** Darker than this is a dark dot, for the speck count. */
    private static final int DARK = 128;
    /** More of the dark dots than this share being lone specks: the picture is speckled. */
    static final double SPECKLED = 0.08;

    private ScanCleanup() {
    }

    /** The picture to read: cleaned where it needs it, else the same picture. */
    public static BufferedImage forReading(BufferedImage picture) {
        int w = picture.getWidth();
        int h = picture.getHeight();
        int[] rgb = picture.getRGB(0, 0, w, h, null, 0, w);
        int[] grey = new int[rgb.length];
        int[] histogram = new int[256];
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            grey[i] = (((p >> 16) & 0xff) * 299 + ((p >> 8) & 0xff) * 587 + (p & 0xff) * 114) / 1000;
            histogram[grey[i]]++;
        }
        int ink = percentile(histogram, rgb.length, 0.01);
        int paper = percentile(histogram, rgb.length, 0.95);
        boolean faint = (ink >= FAINT_INK || paper <= TINTED_PAPER) && paper - ink >= 40;
        boolean speckled = speckled(grey, w, h);
        if (!faint && !speckled) {
            return picture;
        }
        int[] out = grey.clone();
        if (faint) {
            double span = paper - ink;
            for (int i = 0; i < out.length; i++) {
                out[i] = (int) Math.max(0, Math.min(255, Math.round((out[i] - ink) * 255 / span)));
            }
        }
        if (speckled) {
            despeckle(out, w, h);
        }
        BufferedImage cleaned = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] packed = new int[out.length];
        for (int i = 0; i < out.length; i++) {
            packed[i] = (out[i] << 16) | (out[i] << 8) | out[i];
        }
        cleaned.setRGB(0, 0, w, h, packed, 0, w);
        return cleaned;
    }

    private static int percentile(int[] histogram, int total, double share) {
        long want = (long) Math.ceil(total * share);
        long seen = 0;
        for (int v = 0; v < 256; v++) {
            seen += histogram[v];
            if (seen >= want) {
                return v;
            }
        }
        return 255;
    }

    /** Whether many of the dark dots stand alone (no dark neighbour among the eight round them). */
    static boolean speckled(int[] grey, int w, int h) {
        long dark = 0;
        long lone = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                if (grey[y * w + x] >= DARK) {
                    continue;
                }
                dark++;
                if (!hasDarkNeighbour(grey, w, x, y)) {
                    lone++;
                }
            }
        }
        return dark > 0 && (double) lone / dark > SPECKLED;
    }

    private static boolean hasDarkNeighbour(int[] grey, int w, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dy != 0) && grey[(y + dy) * w + x + dx] < DARK) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Lone dark dots, and pairs of them, made paper: in a 4 x 4 window, dark only in its middle 2 x 2. */
    private static void despeckle(int[] grey, int w, int h) {
        int[] src = grey.clone();
        for (int y = 1; y < h - 2; y++) {
            for (int x = 1; x < w - 2; x++) {
                if (src[y * w + x] >= DARK) {
                    continue;
                }
                boolean ringClear = true;
                for (int i = -1; i <= 2 && ringClear; i++) {
                    for (int j = -1; j <= 2; j++) {
                        boolean middle = i >= 0 && i <= 1 && j >= 0 && j <= 1;
                        if (!middle && src[(y + i) * w + x + j] < DARK) {
                            ringClear = false;
                            break;
                        }
                    }
                }
                if (ringClear) {
                    for (int i = 0; i <= 1; i++) {
                        for (int j = 0; j <= 1; j++) {
                            grey[(y + i) * w + x + j] = 255;
                        }
                    }
                }
            }
        }
    }
}
