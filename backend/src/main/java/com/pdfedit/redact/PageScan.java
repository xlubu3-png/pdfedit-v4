package com.pdfedit.redact;

import java.awt.geom.Point2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.state.PDTextState;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/**
 * Runs a page's content and notes where every glyph and every picture lands, with the address of each glyph
 * in the content (which stream, which text operator, which string of it, which code of the string) so that
 * it can be taken out again.
 *
 * <p>Streams are named the way the rewriting names them: {@code page} for the page's own content and
 * {@code page/Fm1} for the form XObject {@code Fm1} that the page draws, and so on down.
 */
final class PageScan extends PDFGraphicsStreamEngine {

    static final String PAGE_STREAM = "page";

    /**
     * One glyph as drawn.
     *
     * @param stream     the content stream it is in
     * @param op         which text-showing operator of that stream (0 = the first)
     * @param string     which string operand of that operator
     * @param code       which character code of that string
     * @param adjust     the TJ number that moves on exactly as far as the glyph did; NaN if there is none
     * @param rewritable whether the glyph can be swapped for that number (not inside a Type 3 glyph or a
     *                   pattern, not written vertically, and the operator's font is known)
     */
    record GlyphAt(String stream, int op, int string, int code, float minX, float minY, float maxX, float maxY,
                   double adjust, boolean rewritable) {

        float centerX() {
            return (minX + maxX) / 2;
        }

        float centerY() {
            return (minY + maxY) / 2;
        }
    }

    /** Where a picture lands on the page. */
    record ImageAt(float minX, float minY, float maxX, float maxY) {
    }

    final List<GlyphAt> glyphs = new ArrayList<>();
    final List<ImageAt> images = new ArrayList<>();
    /** The font each text operator used, by {@code stream#op}: needed to split its strings into codes. */
    final Map<String, PDFont> fonts = new HashMap<>();
    /** How often each form was drawn: a form drawn twice cannot be changed for one place only. */
    final Map<String, Integer> invocations = new HashMap<>();

    private final Deque<Frame> frames = new ArrayDeque<>();
    private String pendingDo;
    private Show show;

    private static final class Frame {
        final String key;
        final boolean inert;
        int count;

        Frame(String key, boolean inert) {
            this.key = key;
            this.inert = inert;
        }
    }

    /** The text operator being run. */
    private static final class Show {
        final String stream;
        final int op;
        final boolean inert;
        int string = -1;
        int code;
        List<Integer> lengths = List.of();

        Show(String stream, int op, boolean inert) {
            this.stream = stream;
            this.op = op;
            this.inert = inert;
        }
    }

    private PageScan(PDPage page) {
        super(page);
    }

    static PageScan of(PDPage page) throws IOException {
        PageScan scan = new PageScan(page);
        scan.processPage(page);
        return scan;
    }

    static String opKey(String stream, int op) {
        return stream + "#" + op;
    }

    /** Whether the stream, or a form that holds it, is drawn more than once. */
    boolean repeated(String stream) {
        String key = stream;
        while (key.contains("/")) {
            if (invocations.getOrDefault(key, 0) > 1) {
                return true;
            }
            key = key.substring(0, key.lastIndexOf('/'));
        }
        return false;
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        String name = operator.getName();
        if (frames.isEmpty()) {
            frames.push(new Frame(PAGE_STREAM, false));
        }
        if (OperatorName.DRAW_OBJECT.equals(name) && !operands.isEmpty()
                && operands.get(operands.size() - 1) instanceof COSName xObject) {
            pendingDo = xObject.getName();
        }
        // ' and " run a Tj of their own: that one belongs to them and is not counted again.
        if (!isShowText(name) || show != null) {
            super.processOperator(operator, operands);
            return;
        }
        Frame frame = frames.peek();
        show = new Show(frame.key, frame.count++, frame.inert);
        try {
            super.processOperator(operator, operands);
        } finally {
            show = null;
        }
    }

    @Override
    protected void showText(byte[] string) throws IOException {
        if (show != null) {
            show.string++;
            show.code = 0;
            PDFont font = getGraphicsState().getTextState().getFont();
            show.lengths = font == null ? List.of() : codeLengths(font, string);
            if (font != null) {
                fonts.put(opKey(show.stream, show.op), font);
            }
        }
        super.showText(string);
    }

    /** How many bytes each character code of the string takes in this font. */
    static List<Integer> codeLengths(PDFont font, byte[] string) throws IOException {
        List<Integer> lengths = new ArrayList<>();
        ByteArrayInputStream in = new ByteArrayInputStream(string);
        while (in.available() > 0) {
            int before = in.available();
            font.readCode(in);
            int used = before - in.available();
            if (used <= 0) {
                break;
            }
            lengths.add(used);
        }
        return lengths;
    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement)
            throws IOException {
        if (show != null) {
            int index = show.code++;
            float w = displacement.getX();
            // The glyph's cell: its advance wide, from a little below the baseline to about the cap height.
            float[] xs = new float[4];
            float[] ys = new float[4];
            float[][] corners = {{0, -0.2f}, {w, -0.2f}, {0, 0.8f}, {w, 0.8f}};
            for (int i = 0; i < 4; i++) {
                Point2D.Float p = textRenderingMatrix.transformPoint(corners[i][0], corners[i][1]);
                xs[i] = p.x;
                ys[i] = p.y;
            }
            PDTextState state = getGraphicsState().getTextState();
            float size = state.getFontSize();
            boolean space = code == 32 && index < show.lengths.size() && show.lengths.get(index) == 1;
            double advance = w * size + state.getCharacterSpacing() + (space ? state.getWordSpacing() : 0);
            double adjust = size == 0 ? Double.NaN : -advance * 1000.0 / size;
            boolean rewritable = !show.inert && !font.isVertical() && !Double.isNaN(adjust)
                    && index < show.lengths.size();
            glyphs.add(new GlyphAt(show.stream, show.op, show.string, index, min(xs), min(ys), max(xs), max(ys),
                    adjust, rewritable));
        }
        super.showGlyph(textRenderingMatrix, font, code, displacement);
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
        enter(() -> super.showForm(form));
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup group) throws IOException {
        enter(() -> super.showTransparencyGroup(group));
    }

    private interface Body {
        void run() throws IOException;
    }

    private void enter(Body body) throws IOException {
        String parent = frames.isEmpty() ? PAGE_STREAM : frames.peek().key;
        boolean inert = !frames.isEmpty() && frames.peek().inert;
        String key = parent + "/" + pendingDo;
        invocations.merge(key, 1, Integer::sum);
        frames.push(new Frame(key, inert));
        Show outer = show;
        show = null;
        try {
            body.run();
        } finally {
            show = outer;
            frames.pop();
        }
    }

    @Override
    protected void processType3Stream(PDType3CharProc charProc, Matrix textRenderingMatrix) throws IOException {
        // What a Type 3 glyph draws is part of that glyph, not of the page's own text.
        frames.push(new Frame("type3", true));
        Show outer = show;
        show = null;
        try {
            super.processType3Stream(charProc, textRenderingMatrix);
        } finally {
            show = outer;
            frames.pop();
        }
    }

    @Override
    public void drawImage(PDImage pdImage) {
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        float[] xs = new float[4];
        float[] ys = new float[4];
        float[][] corners = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
        for (int i = 0; i < 4; i++) {
            Point2D.Float p = ctm.transformPoint(corners[i][0], corners[i][1]);
            xs[i] = p.x;
            ys[i] = p.y;
        }
        images.add(new ImageAt(min(xs), min(ys), max(xs), max(ys)));
    }

    private static float min(float[] values) {
        float m = values[0];
        for (float v : values) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static float max(float[] values) {
        float m = values[0];
        for (float v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    static boolean isShowText(String operator) {
        return OperatorName.SHOW_TEXT.equals(operator) || OperatorName.SHOW_TEXT_ADJUSTED.equals(operator)
                || OperatorName.SHOW_TEXT_LINE.equals(operator) || OperatorName.SHOW_TEXT_LINE_AND_SPACE.equals(operator);
    }

    // Paths are of no interest: what they cover stays covered by the black box.

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
    }

    @Override
    public void clip(int windingRule) {
    }

    @Override
    public void moveTo(float x, float y) {
    }

    @Override
    public void lineTo(float x, float y) {
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
    }

    @Override
    public Point2D getCurrentPoint() {
        return new Point2D.Float();
    }

    @Override
    public void closePath() {
    }

    @Override
    public void endPath() {
    }

    @Override
    public void strokePath() {
    }

    @Override
    public void fillPath(int windingRule) {
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
    }

    @Override
    public void shadingFill(COSName shadingName) {
    }
}
