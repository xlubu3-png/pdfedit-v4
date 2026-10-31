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

        List<float[]> boxes = detect(rgb, width, height);
        List<int[]> crops = new ArrayList<>(boxes.size());
        List<int[]> sizes = new ArrayList<>(boxes.size());
        for (float[] box : boxes) {
            BufferedImage crop = crop(image, box);
            crops.add(crop.getRGB(0, 0, crop.getWidth(), crop.getHeight(), null, 0, crop.getWidth()));
            sizes.add(new int[] {crop.getWidth(), crop.getHeight()});
        }
        Read[] reads = new Read[boxes.size()];
        recognize(crops, sizes, reads);

        List<OcrLine> lines = new ArrayList<>();
        for (int k = 0; k < boxes.size(); k++) {
            if (reads[k].text().isBlank() || reads[k].score() < MIN_SCORE) {
                continue;
            }
            float[] corners = boxes.get(k).clone();
            for (int c = 0; c < corners.length; c++) {
                corners[c] /= (float) scale;
            }
            lines.add(new OcrLine(reads[k].text(), reads[k].score(), corners, reads[k].centers()));
        }
        return lines;
    }

    /** What the reader made of one line; {@code centers} as in {@link OcrLine#centers()}. */
    private record Read(String text, float score, float[] centers) {
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
     * The box cut out and turned straight (bicubic), as PaddleOCR's {@code get_rotate_crop_image}; a box much
     * taller than wide is a vertical line and is turned a quarter left.
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
        if (h >= 1.5 * w) {
            BufferedImage turned = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    turned.setRGB(y, w - 1 - x, out.getRGB(x, y));
                }
            }
            return turned;
        }
        return out;
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
        return new Read(out.toString(), score, at);
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
