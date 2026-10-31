package com.pdfedit.ocr;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Reads the text on a picture with the PaddleOCR PP-OCRv5 models (src/main/resources/ocr): one model finds the
 * lines, the Korean one reads each line. The steps around the models follow RapidOCR, which the models were
 * tested with, so that the results match the test (README there).
 *
 * <p>Thread-safe; one picture is read at a time, since each already uses all processor cores.
 */
public final class OcrEngine implements AutoCloseable {

    /** The detector works on pictures whose shorter side is at least this (smaller ones are enlarged). */
    private static final int DET_MIN_SIDE = 736;
    /** Bigger pictures are reduced first: more pixels than this cost time and add nothing at 200-300 dpi. */
    private static final int MAX_SIDE = 4000;
    private static final int REC_HEIGHT = 48;
    private static final int REC_MIN_WIDTH = 320;
    private static final int REC_BATCH = 6;
    /** Lines read with less confidence than this are dropped, as RapidOCR does. */
    private static final float MIN_SCORE = 0.5f;
    /** A line this many times taller than wide is vertical (as PaddleOCR decides). */
    static final double VERTICAL = 1.5;
    /** Pixels darker than this (luminance 0-255) belong to a line, for finding table rules (often light grey). */
    private static final int RULE_DARKNESS = 215;
    /** A reading this confident is taken as right (for choosing how to read a vertical line). */
    private static final float SURE = 0.9f;

    private final OrtEnvironment env;
    private final OrtSession detector;
    private final OrtSession reader;
    private final String detInput;
    private final String recInput;
    /** Index 0 is the CTC blank; the last entry is a space. */
    private final String[] characters;

    private OcrEngine(OrtEnvironment env, OrtSession detector, OrtSession reader) throws OrtException {
        this.env = env;
        this.detector = detector;
        this.reader = reader;
        this.detInput = detector.getInputNames().iterator().next();
        this.recInput = reader.getInputNames().iterator().next();
        String list = reader.getMetadata().getCustomMetadata().get("character");
        if (list == null) {
            throw new IllegalStateException("The recognition model carries no character list");
        }
        String[] chars = list.split("\\r?\\n", -1);
        this.characters = new String[chars.length + 2];
        this.characters[0] = "";
        System.arraycopy(chars, 0, this.characters, 1, chars.length);
        this.characters[chars.length + 1] = " ";
    }

    /** The engine with the models bundled with the app. */
    public static OcrEngine load() throws IOException, OrtException {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            OrtSession detector = env.createSession(model("det.onnx"), options);
            OrtSession reader = env.createSession(model("rec-korean.onnx"), options);
            return new OcrEngine(env, detector, reader);
        }
    }

    private static byte[] model(String name) throws IOException {
        try (InputStream in = OcrEngine.class.getResourceAsStream("/ocr/" + name)) {
            if (in == null) {
                throw new IOException("OCR model missing: " + name);
            }
            return in.readAllBytes();
        }
    }

    /** The lines of text on the picture, top to bottom, with their corners in the picture's pixels. */
    public synchronized List<OcrLine> read(BufferedImage picture) throws OrtException {
        BufferedImage image = picture;
        double scale = 1;
        if (Math.max(picture.getWidth(), picture.getHeight()) > MAX_SIDE) {
            scale = (double) MAX_SIDE / Math.max(picture.getWidth(), picture.getHeight());
            image = scaled(picture, (int) (picture.getWidth() * scale), (int) (picture.getHeight() * scale));
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int[] rgb = image.getRGB(0, 0, width, height, null, 0, width);

        List<float[]> boxes = joinColumns(detect(rgb, width, height), rgb, width);
        List<int[]> crops = new ArrayList<>(boxes.size());
        List<int[]> sizes = new ArrayList<>(boxes.size());
        List<Way> ways = new ArrayList<>(boxes.size());
        for (int k = 0; k < boxes.size(); k++) {
            BufferedImage crop = crop(image, boxes.get(k));
            if (crop.getHeight() >= VERTICAL * crop.getWidth()) {
                // A vertical line: turned a quarter left (read downwards), a quarter right (read upwards), or
                // letters standing one under the other, set side by side. The likeliest reading wins.
                addCrop(crops, sizes, ways, turned(crop, true), new Way(k, Way.DOWN, null));
                addCrop(crops, sizes, ways, turned(crop, false), new Way(k, Way.UP, null));
                Stacked stacked = Stacked.of(crop);
                if (stacked != null) {
                    addCrop(crops, sizes, ways, stacked.image(), new Way(k, Way.STACKED, stacked));
                }
            } else {
                addCrop(crops, sizes, ways, crop, new Way(k, Way.ACROSS, null));
            }
        }
        Read[] candidates = new Read[crops.size()];
        recognize(crops, sizes, candidates);
        Read[] reads = new Read[boxes.size()];
        Read[] downReads = new Read[boxes.size()];
        Read[] upReads = new Read[boxes.size()];
        Read[] stackedReads = new Read[boxes.size()];
        int[] stackedLetters = new int[boxes.size()];
        for (int c = 0; c < candidates.length; c++) {
            Way way = ways.get(c);
            Read read = way.fromTop(candidates[c]);
            switch (way.kind()) {
                case Way.DOWN -> downReads[way.box()] = read;
                case Way.UP -> upReads[way.box()] = read;
                case Way.STACKED -> {
                    stackedReads[way.box()] = read;
                    stackedLetters[way.box()] = way.stacked().starts().length;
                }
                default -> reads[way.box()] = read;
            }
        }
        for (int k = 0; k < reads.length; k++) {
            if (downReads[k] != null) {
                reads[k] = vertical(downReads[k], upReads[k], stackedReads[k], stackedLetters[k]);
            }
        }

        List<OcrLine> lines = new ArrayList<>();
        for (int k = 0; k < boxes.size(); k++) {
            if (reads[k].text().isBlank() || reads[k].score() < MIN_SCORE) {
                continue;
            }
            float[] corners = boxes.get(k).clone();
            for (int c = 0; c < corners.length; c++) {
                corners[c] /= (float) scale;
            }
            lines.add(new OcrLine(reads[k].text(), reads[k].score(), corners, reads[k].centers(), reads[k].orientation()));
        }
        return lines;
    }

    /**
     * The boxes, with those that are letters of one column joined: a label written down the page with wide gaps
     * between its letters is found as a box per letter or two (대 / 표자), and read like that it says nothing
     * sensible. Two boxes are one column when both are no wider than tall (no horizontal word), about as wide as
     * each other, one right under the other, less than a letter apart - and no table rule runs between them:
     * cells of a table one under the other (남 / 여) are not one label.
     *
     * @param rgb   the picture the boxes were found on, row by row
     * @param width its width
     */
    static List<float[]> joinColumns(List<float[]> boxes, int[] rgb, int width) {
        List<float[]> rects = new ArrayList<>(boxes.size());
        for (float[] box : boxes) {
            float left = Math.min(Math.min(box[0], box[2]), Math.min(box[4], box[6]));
            float right = Math.max(Math.max(box[0], box[2]), Math.max(box[4], box[6]));
            float top = Math.min(Math.min(box[1], box[3]), Math.min(box[5], box[7]));
            float bottom = Math.max(Math.max(box[1], box[3]), Math.max(box[5], box[7]));
            rects.add(new float[] {left, top, right, bottom});
        }
        boolean[] gone = new boolean[boxes.size()];
        float[][] joined = new float[boxes.size()][];
        boolean any = false;
        for (int a = 0; a < rects.size(); a++) {
            if (gone[a] || !columnLike(rects.get(a))) {
                continue;
            }
            float[] column = rects.get(a).clone();
            boolean grew = true;
            while (grew) {
                grew = false;
                for (int b = 0; b < rects.size(); b++) {
                    if (b != a && !gone[b] && columnLike(rects.get(b)) && sameColumn(column, rects.get(b))
                            && !ruleBetween(column, rects.get(b), rgb, width)) {
                        float[] r = rects.get(b);
                        column = new float[] {Math.min(column[0], r[0]), Math.min(column[1], r[1]),
                                Math.max(column[2], r[2]), Math.max(column[3], r[3])};
                        gone[b] = true;
                        joined[a] = column;
                        grew = true;
                        any = true;
                    }
                }
            }
        }
        if (!any) {
            return boxes;
        }
        List<float[]> out = new ArrayList<>(boxes.size());
        for (int k = 0; k < boxes.size(); k++) {
            if (gone[k]) {
                continue;
            }
            float[] r = joined[k];
            out.add(r == null ? boxes.get(k) : new float[] {r[0], r[1], r[2], r[1], r[2], r[3], r[0], r[3]});
        }
        return out;
    }

    /** No wider than tall: a letter, or letters one under the other - not a word written across. */
    private static boolean columnLike(float[] r) {
        return r[2] - r[0] <= 1.25f * (r[3] - r[1]);
    }

    /**
     * Whether a line runs across between the middles of the column and {@code r} (a table rule) over nearly all
     * of their common width. The boxes are drawn wide enough to take in a rule close to the letters, so it is
     * looked for from middle to middle, not only in the gap between them; the boxes' margins keep letters from
     * reaching that far across.
     */
    private static boolean ruleBetween(float[] column, float[] r, int[] rgb, int width) {
        int height = rgb.length / Math.max(width, 1);
        boolean below = r[1] >= column[1];
        float upperMiddle = below ? (column[1] + column[3]) / 2 : (r[1] + r[3]) / 2;
        float lowerMiddle = below ? (r[1] + r[3]) / 2 : (column[1] + column[3]) / 2;
        int from = (int) Math.ceil(upperMiddle);
        int to = (int) Math.floor(lowerMiddle);
        int left = (int) Math.ceil(Math.max(column[0], r[0]));
        int right = (int) Math.floor(Math.min(column[2], r[2]));
        if (right <= left) {
            return false;
        }
        for (int y = Math.max(0, from); y <= Math.min(height - 1, to); y++) {
            int dark = 0;
            for (int x = Math.max(0, left); x < Math.min(width, right); x++) {
                int p = rgb[y * width + x];
                if (((p >> 16 & 0xFF) * 299 + (p >> 8 & 0xFF) * 587 + (p & 0xFF) * 114) / 1000 < RULE_DARKNESS) {
                    dark++;
                }
            }
            if (dark >= 0.9 * (right - left)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code r} lies right above or under the column, about as wide and less than a letter away. */
    private static boolean sameColumn(float[] column, float[] r) {
        float cw = column[2] - column[0];
        float rw = r[2] - r[0];
        if (Math.min(cw, rw) < 0.65f * Math.max(cw, rw)) {
            return false;
        }
        float overlap = Math.min(column[2], r[2]) - Math.max(column[0], r[0]);
        if (overlap < 0.7f * Math.min(cw, rw)) {
            return false;
        }
        float gap = Math.max(r[1] - column[3], column[1] - r[3]);
        return gap < Math.min(cw, rw);
    }

    /** What the reader made of one line; {@code centers} and {@code orientation} as in {@link OcrLine}. */
    private record Read(String text, float score, float[] centers, String orientation) {
    }

    /**
     * The reading of a vertical line: turned downwards, turned upwards, or its letters set side by side. The
     * reader knows letters lying on their side nearly as well as upright ones, so a sure side-by-side reading says
     * nothing about the order of a turned line: read side by side, a line running upwards comes out backwards.
     * <ul>
     * <li>Side by side has the letters of the downward reading: taken, with their places and without the spaces
     * the gaps between letters one under the other make when turned.
     * <li>Side by side has them backwards of the upward reading: a line turned left; the upward reading.
     * <li>Side by side has as many letters as stand one under the other and the turned reading does not (it
     * missed some, as a turned short label often does): side by side.
     * <li>Otherwise the surer turned reading, unless only side by side is sure.
     * </ul>
     *
     * @param letters how many letters stand one under the other in the picture
     */
    private static Read vertical(Read down, Read up, Read stacked, int letters) {
        Read turned = up != null && up.score() > down.score() ? up : down;
        if (stacked == null) {
            return turned;
        }
        String side = stacked.text();
        if (side.equals(down.text().replace(" ", ""))) {
            return stacked;
        }
        if (up != null && new StringBuilder(side).reverse().toString().equals(up.text().replace(" ", ""))) {
            return up;
        }
        boolean sideCounts = side.codePointCount(0, side.length()) == letters;
        String turnedLetters = turned.text().replace(" ", "");
        boolean turnedCounts = turnedLetters.codePointCount(0, turnedLetters.length()) == letters;
        if (sideCounts && !turnedCounts && stacked.score() >= SURE) {
            return stacked;
        }
        return turned.score() < SURE && stacked.score() >= SURE ? stacked : turned;
    }

    private static void addCrop(List<int[]> crops, List<int[]> sizes, List<Way> ways, BufferedImage crop, Way way) {
        crops.add(crop.getRGB(0, 0, crop.getWidth(), crop.getHeight(), null, 0, crop.getWidth()));
        sizes.add(new int[] {crop.getWidth(), crop.getHeight()});
        ways.add(way);
    }

    /**
     * How a line's picture was laid out for the reader. {@code fromTop} turns where its letters were seen into
     * places along the line as {@link OcrLine#centers()} gives them: from the left, or for a vertical line from
     * the top.
     */
    private record Way(int box, int kind, Stacked stacked) {
        static final int ACROSS = 0;
        static final int DOWN = 1;
        static final int UP = 2;
        static final int STACKED = 3;

        Read fromTop(Read read) {
            float[] at = read.centers().clone();
            String text = read.text();
            if (kind == UP) {
                for (int i = 0; i < at.length; i++) {
                    at[i] = 1 - at[i];
                }
            } else if (kind == STACKED) {
                for (int i = 0; i < at.length; i++) {
                    at[i] = stacked.fromTop(at[i]);
                }
                // Letters set one under the other have no spaces between words to see.
                StringBuilder letters = new StringBuilder();
                List<Float> kept = new ArrayList<>();
                for (int i = 0; i < text.length(); i++) {
                    if (text.charAt(i) != ' ') {
                        letters.append(text.charAt(i));
                        kept.add(at[i]);
                    }
                }
                text = letters.toString();
                at = new float[kept.size()];
                for (int i = 0; i < at.length; i++) {
                    at[i] = kept.get(i);
                }
            }
            return new Read(text, read.score(), at, kind == DOWN ? OcrLine.DOWN : kind == UP ? OcrLine.UP
                    : kind == STACKED ? OcrLine.STACKED : null);
        }
    }

    /**
     * A vertical line of upright letters, one under the other, set side by side as a horizontal line the reader
     * knows how to read. The letters are found by the rows of ink between empty rows; pieces of one letter (the
     * parts of 흐) are joined while together they are no taller than a letter is wide.
     *
     * @param image  the letters side by side
     * @param starts for every letter, the first row it has in the vertical picture
     * @param ends   for every letter, the row after its last
     * @param lefts  for every letter, its first column in {@code image}
     * @param rights for every letter, the column after its last in {@code image}
     */
    record Stacked(BufferedImage image, int height, int[] starts, int[] ends, int[] lefts, int[] rights) {

        /** Summed difference of the colour channels from the background from which a pixel is ink. */
        private static final int INK = 120;

        /** Null when there are not at least two letters one under the other. */
        static Stacked of(BufferedImage vertical) {
            int w = vertical.getWidth();
            int h = vertical.getHeight();
            int background = edgeColour(vertical);
            int[] inkInRow = new int[h];
            int left = w;
            int right = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (difference(vertical.getRGB(x, y), background) > INK) {
                        inkInRow[y]++;
                        left = Math.min(left, x);
                        right = Math.max(right, x);
                    }
                }
            }
            if (right < left) {
                return null;
            }
            int letterWidth = right - left + 1;
            List<int[]> pieces = new ArrayList<>();
            for (int y = 0; y < h; y++) {
                if (inkInRow[y] > 0 && (y == 0 || inkInRow[y - 1] == 0)) {
                    pieces.add(new int[] {y, y + 1});
                } else if (inkInRow[y] > 0) {
                    pieces.getLast()[1] = y + 1;
                }
            }
            List<int[]> letters = new ArrayList<>();
            for (int[] piece : pieces) {
                int[] last = letters.isEmpty() ? null : letters.getLast();
                if (last != null && piece[1] - last[0] <= 1.1 * letterWidth) {
                    last[1] = piece[1];
                } else {
                    letters.add(piece.clone());
                }
            }
            if (letters.size() < 2) {
                return null;
            }

            int pad = Math.max(2, letterWidth / 4);
            int gap = Math.max(2, letterWidth / 5);
            int tallest = letters.stream().mapToInt(l -> l[1] - l[0]).max().orElse(letterWidth);
            int outHeight = Math.max(letterWidth, tallest) + 2 * pad;
            int outWidth = 2 * pad + letters.size() * letterWidth + (letters.size() - 1) * gap;
            BufferedImage out = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            int[] starts = new int[letters.size()];
            int[] ends = new int[letters.size()];
            int[] lefts = new int[letters.size()];
            int[] rights = new int[letters.size()];
            try {
                g.setColor(new Color(background));
                g.fillRect(0, 0, outWidth, outHeight);
                for (int i = 0; i < letters.size(); i++) {
                    int[] l = letters.get(i);
                    int x = pad + i * (letterWidth + gap);
                    int y = (outHeight - (l[1] - l[0])) / 2;
                    g.drawImage(vertical.getSubimage(left, l[0], letterWidth, l[1] - l[0]), x, y, null);
                    starts[i] = l[0];
                    ends[i] = l[1];
                    lefts[i] = x;
                    rights[i] = x + letterWidth;
                }
            } finally {
                g.dispose();
            }
            return new Stacked(out, h, starts, ends, lefts, rights);
        }

        /** Where a place along the side-by-side line (0 to 1) is in the vertical picture, from its top (0 to 1). */
        float fromTop(float along) {
            double x = along * image.getWidth();
            int i = 0;
            while (i < lefts.length - 1 && x > (rights[i] + lefts[i + 1]) / 2.0) {
                i++;
            }
            double within = Math.max(0, Math.min(1, (x - lefts[i]) / (double) (rights[i] - lefts[i])));
            return (float) ((starts[i] + within * (ends[i] - starts[i])) / height);
        }

        /** The colour at the picture's edge: its background. */
        private static int edgeColour(BufferedImage image) {
            long r = 0;
            long g = 0;
            long b = 0;
            int n = 0;
            for (int x = 0; x < image.getWidth(); x++) {
                for (int y : new int[] {0, image.getHeight() - 1}) {
                    int rgb = image.getRGB(x, y);
                    r += rgb >> 16 & 0xFF;
                    g += rgb >> 8 & 0xFF;
                    b += rgb & 0xFF;
                    n++;
                }
            }
            return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        }

        private static int difference(int rgb, int other) {
            return Math.abs((rgb >> 16 & 0xFF) - (other >> 16 & 0xFF)) + Math.abs((rgb >> 8 & 0xFF) - (other >> 8 & 0xFF))
                    + Math.abs((rgb & 0xFF) - (other & 0xFF));
        }
    }

    private List<float[]> detect(int[] rgb, int width, int height) throws OrtException {
        double ratio = Math.min(width, height) < DET_MIN_SIDE ? (double) DET_MIN_SIDE / Math.min(width, height) : 1;
        int dw = (int) (Math.rint((int) (width * ratio) / 32.0) * 32);
        int dh = (int) (Math.rint((int) (height * ratio) / 32.0) * 32);
        if (dw <= 0 || dh <= 0) {
            return List.of();
        }
        float[] input = new float[3 * dw * dh];
        putResized(rgb, width, height, dw, dh, input, 0, dw, dw * dh);
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[] {1, 3, dh, dw});
                OrtSession.Result result = detector.run(Map.of(detInput, tensor))) {
            OnnxTensor out = (OnnxTensor) result.get(0);
            long[] shape = out.getInfo().getShape();
            int mh = (int) shape[2];
            int mw = (int) shape[3];
            float[] prob = new float[mw * mh];
            out.getFloatBuffer().get(prob);
            return TextBoxes.find(prob, mw, mh, width, height);
        }
    }

    /**
     * The box cut out and turned straight (bicubic), as PaddleOCR's {@code get_rotate_crop_image}; a vertical
     * line stays standing, {@link #read} decides how to read it.
     */
    private static BufferedImage crop(BufferedImage image, float[] box) {
        double x0 = box[0];
        double y0 = box[1];
        int w = (int) Math.max(Math.hypot(box[2] - x0, box[3] - y0), Math.hypot(box[4] - box[6], box[5] - box[7]));
        int h = (int) Math.max(Math.hypot(box[6] - x0, box[7] - y0), Math.hypot(box[4] - box[2], box[5] - box[3]));
        w = Math.max(w, 1);
        h = Math.max(h, 1);
        // Map the box's top-left, top-right and bottom-left corners onto (0,0), (w,0) and (0,h).
        double ux = box[2] - x0;
        double uy = box[3] - y0;
        double vx = box[6] - x0;
        double vy = box[7] - y0;
        double det = ux * vy - uy * vx;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            if (det != 0) {
                // diag(w, h) times the inverse of [u v]; pixel centres sit at +0.5 in Java2D.
                double a = w * vy / det;
                double b = -w * vx / det;
                double c = -h * uy / det;
                double d = h * ux / det;
                AffineTransform toBox = new AffineTransform();
                toBox.translate(0.5, 0.5);
                toBox.concatenate(new AffineTransform(a, c, b, d, 0, 0));
                toBox.translate(-x0 - 0.5, -y0 - 0.5);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(image, toBox, null);
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * The picture turned a quarter: left, so what ran downwards runs left to right (as PaddleOCR turns a vertical
     * line), or right, so what ran upwards does.
     */
    private static BufferedImage turned(BufferedImage picture, boolean left) {
        int w = picture.getWidth();
        int h = picture.getHeight();
        BufferedImage turned = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (left) {
                    turned.setRGB(y, w - 1 - x, picture.getRGB(x, y));
                } else {
                    turned.setRGB(h - 1 - y, x, picture.getRGB(x, y));
                }
            }
        }
        return turned;
    }

    private void recognize(List<int[]> crops, List<int[]> sizes, Read[] reads) throws OrtException {
        Integer[] order = new Integer[crops.size()];
        for (int k = 0; k < order.length; k++) {
            order[k] = k;
        }
        Arrays.sort(order, Comparator.comparingDouble(k -> (double) sizes.get(k)[0] / sizes.get(k)[1]));
        for (int begin = 0; begin < order.length; begin += REC_BATCH) {
            int end = Math.min(order.length, begin + REC_BATCH);
            double maxRatio = (double) REC_MIN_WIDTH / REC_HEIGHT;
            for (int i = begin; i < end; i++) {
                int[] size = sizes.get(order[i]);
                maxRatio = Math.max(maxRatio, (double) size[0] / size[1]);
            }
            int width = (int) (REC_HEIGHT * maxRatio);
            int n = end - begin;
            int plane = REC_HEIGHT * width;
            float[] input = new float[n * 3 * plane];
            int[] resizedWidths = new int[n];
            for (int i = begin; i < end; i++) {
                int[] size = sizes.get(order[i]);
                double ratio = (double) size[0] / size[1];
                int resized = Math.max((int) Math.min(Math.ceil(REC_HEIGHT * ratio), width), 1);
                resizedWidths[i - begin] = resized;
                putResized(crops.get(order[i]), size[0], size[1], resized, REC_HEIGHT, input,
                        (i - begin) * 3 * plane, width, plane);
            }
            try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input),
                    new long[] {n, 3, REC_HEIGHT, width});
                    OrtSession.Result result = reader.run(Map.of(recInput, tensor))) {
                OnnxTensor out = (OnnxTensor) result.get(0);
                long[] shape = out.getInfo().getShape();
                int steps = (int) shape[1];
                int classes = (int) shape[2];
                float[] probs = new float[n * steps * classes];
                out.getFloatBuffer().get(probs);
                for (int i = 0; i < n; i++) {
                    // Step t of the output covers the input's columns around (t + 0.5) * width / steps.
                    double stepWidth = (double) width / steps / resizedWidths[i];
                    reads[order[begin + i]] = decode(probs, i * steps * classes, steps, classes, stepWidth);
                }
            }
        }
    }

    /**
     * Greedy CTC: the likeliest class at every step, repeats merged, blanks dropped. On some lines the Korean
     * model gives no space at all; there a space is put where two letters are read further apart than letters
     * of a word are ({@link #spaceGap}). A line where the model did give spaces keeps its own: those come from
     * text set wide (justified), where gaps tell nothing.
     */
    private Read decode(float[] probs, int base, int steps, int classes, double stepWidth) {
        List<String> text = new ArrayList<>();
        List<Float> textAt = new ArrayList<>();
        List<String> spaced = new ArrayList<>();
        List<Float> spacedAt = new ArrayList<>();
        boolean modelSpaces = false;
        double sum = 0;
        int count = 0;
        int previous = -1;
        int lastStep = -1;
        String lastChar = null;
        for (int t = 0; t < steps; t++) {
            int best = 0;
            float bestProb = probs[base + t * classes];
            for (int c = 1; c < classes; c++) {
                float p = probs[base + t * classes + c];
                if (p > bestProb) {
                    best = c;
                    bestProb = p;
                }
            }
            if (best != previous && best != 0 && best < characters.length) {
                String ch = characters[best];
                float at = (float) Math.min(1, (t + 0.5) * stepWidth);
                if (ch.isBlank()) {
                    modelSpaces = true;
                } else {
                    if (lastChar != null && t - lastStep > spaceGap(lastChar, ch)) {
                        spaced.add(" ");
                        spacedAt.add((float) Math.min(1, (lastStep + t + 1) / 2.0 * stepWidth));
                    }
                    lastChar = ch;
                    lastStep = t;
                    spaced.add(ch);
                    spacedAt.add(at);
                }
                text.add(ch);
                textAt.add(at);
                sum += bestProb;
                count++;
            }
            previous = best;
        }
        return tidy(modelSpaces ? text : spaced, modelSpaces ? textAt : spacedAt,
                count == 0 ? 0 : (float) (sum / count));
    }

    /**
     * The pieces joined, one space between words and none at the ends; every char of the result keeps the place
     * (0 to 1 along the line) of the piece it came from.
     */
    private static Read tidy(List<String> pieces, List<Float> places, float score) {
        StringBuilder out = new StringBuilder();
        List<Float> centers = new ArrayList<>();
        for (int k = 0; k < pieces.size(); k++) {
            String piece = pieces.get(k).isBlank() ? " " : pieces.get(k);
            boolean space = piece.equals(" ");
            if (space && (out.isEmpty() || out.charAt(out.length() - 1) == ' ')) {
                continue;
            }
            for (int c = 0; c < piece.length(); c++) {
                out.append(piece.charAt(c));
                centers.add(places.get(k));
            }
        }
        while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
            out.setLength(out.length() - 1);
            centers.removeLast();
        }
        float[] at = new float[centers.size()];
        for (int k = 0; k < at.length; k++) {
            at[k] = centers.get(k);
        }
        return new Read(out.toString(), score, at, null);
    }

    /**
     * How many steps apart two letters can be read and still belong to one word. Measured on typeset Korean
     * documents (lines scaled to 48 pixels high, as the reader sees them): Hangul letters of a word come 3-5
     * steps apart and 6 or more across a space; digits and Latin letters up to 4; next to punctuation up to 3.
     * Digits next to digits are never split: a narrow "1" leaves a wide gap, and numbers are seldom spaced.
     */
    static int spaceGap(String before, String after) {
        char a = before.charAt(0);
        char b = after.charAt(0);
        if (Character.isDigit(a) && Character.isDigit(b)) {
            return Integer.MAX_VALUE;
        }
        if (isHangul(a) && isHangul(b)) {
            return 5;
        }
        if (!Character.isLetterOrDigit(a) || !Character.isLetterOrDigit(b)) {
            return 3;
        }
        return 4;
    }

    private static boolean isHangul(char c) {
        return (c >= '가' && c <= '힣') || (c >= 'ㄱ' && c <= 'ㆎ');
    }

    /**
     * Writes the picture resized to {@code dw x dh} (bilinear, pixel centres aligned as OpenCV's INTER_LINEAR)
     * into {@code out} as three planes, blue, green, red (the order the models were trained on), each value
     * scaled to -1..1. Rows are {@code rowStride} apart and planes {@code planeStride}; the rest stays 0.
     */
    static void putResized(int[] rgb, int sw, int sh, int dw, int dh, float[] out, int base, int rowStride,
            int planeStride) {
        int[] x0 = new int[dw];
        int[] x1 = new int[dw];
        float[] ax = new float[dw];
        axis(sw, dw, x0, x1, ax);
        int[] y0 = new int[dh];
        int[] y1 = new int[dh];
        float[] ay = new float[dh];
        axis(sh, dh, y0, y1, ay);
        for (int y = 0; y < dh; y++) {
            int r0 = y0[y] * sw;
            int r1 = y1[y] * sw;
            float wy = ay[y];
            for (int x = 0; x < dw; x++) {
                int p00 = rgb[r0 + x0[x]];
                int p01 = rgb[r0 + x1[x]];
                int p10 = rgb[r1 + x0[x]];
                int p11 = rgb[r1 + x1[x]];
                float wx = ax[x];
                int at = base + y * rowStride + x;
                for (int c = 0; c < 3; c++) {
                    int shift = 8 * c; // blue, green, red
                    float top = ((p00 >> shift) & 0xff) * (1 - wx) + ((p01 >> shift) & 0xff) * wx;
                    float bottom = ((p10 >> shift) & 0xff) * (1 - wx) + ((p11 >> shift) & 0xff) * wx;
                    float v = top * (1 - wy) + bottom * wy;
                    out[at + c * planeStride] = v / 127.5f - 1f;
                }
            }
        }
    }

    private static void axis(int src, int dst, int[] i0, int[] i1, float[] weight) {
        double step = (double) src / dst;
        for (int d = 0; d < dst; d++) {
            double f = (d + 0.5) * step - 0.5;
            int s = (int) Math.floor(f);
            double w = f - s;
            if (s < 0) {
                s = 0;
                w = 0;
            }
            if (s >= src - 1) {
                s = src - 1;
                w = 0;
            }
            i0[d] = s;
            i1[d] = Math.min(s + 1, src - 1);
            weight[d] = (float) w;
        }
    }

    private static BufferedImage scaled(BufferedImage image, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    @Override
    public void close() throws OrtException {
        detector.close();
        reader.close();
    }
}
