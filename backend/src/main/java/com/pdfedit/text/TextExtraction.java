package com.pdfedit.text;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor;
import org.apache.pdfbox.contentstream.operator.color.SetStrokingColor;
import org.apache.pdfbox.contentstream.operator.color.SetStrokingColorN;
import org.apache.pdfbox.contentstream.operator.color.SetStrokingColorSpace;
import org.apache.pdfbox.contentstream.operator.color.SetStrokingDeviceCMYKColor;
import org.apache.pdfbox.contentstream.operator.color.SetStrokingDeviceGrayColor;
import org.apache.pdfbox.contentstream.operator.color.SetStrokingDeviceRGBColor;
import org.apache.pdfbox.contentstream.operator.state.SetLineWidth;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

/**
 * Extracts per-run text positions from a single PDF page, converted into PDF user space
 * (origin bottom-left, y-up) so results line up directly with {@code PDPageContentStream}
 * drawing calls. Each run also records how every one of its characters was drawn.
 */
public final class TextExtraction {

    /** The page's own content stream, as {@link ShowOpRef#stream()} names it. */
    public static final String PAGE_STREAM = "page";

    private TextExtraction() {
    }

    /** Graphics state in effect when a character was drawn. */
    private record DrawState(int renderMode, float lineWidth, PDColor fill, PDColor stroke) {
    }

    /** Operators counted so far in one content stream being run. */
    private static final class Frame {
        final String key;
        final boolean inert;
        int count;

        Frame(String key, boolean inert) {
            this.key = key;
            this.inert = inert;
        }
    }

    /** What is collected about an operator while its characters arrive. */
    private static final class OpBuilder {
        final ShowOpRef ref;
        final String operator;
        double adjust = Double.NaN;
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float baseline;
        float size;
        boolean seen;

        OpBuilder(ShowOpRef ref, String operator) {
            this.ref = ref;
            this.operator = operator;
        }
    }

    public static List<TextRun> extractRuns(PDDocument doc, int pageIndex) throws IOException {
        return extractWithOps(doc, pageIndex).runs();
    }

    /** The runs of a page and, for each, the operators of the content stream that drew it. */
    public static PageExtraction extractWithOps(PDDocument doc, int pageIndex) throws IOException {
        List<TextRun> runs = new ArrayList<>();
        List<Set<ShowOpRef>> runOps = new ArrayList<>();
        PDPage page = doc.getPage(pageIndex);
        // PDFTextStripper reports positions relative to the crop box's lower-left corner.
        PDRectangle crop = page.getCropBox();
        float originX = crop.getLowerLeftX();
        float originY = crop.getLowerLeftY();
        float pageHeight = crop.getHeight();
        Map<TextPosition, DrawState> states = new IdentityHashMap<>();
        Map<TextPosition, ShowOpRef> opOf = new IdentityHashMap<>();
        Map<ShowOpRef, OpBuilder> builders = new LinkedHashMap<>();
        Map<String, Integer> invocations = new HashMap<>();

        PDFTextStripper stripper = new PDFTextStripper() {
            private final Deque<Frame> frames = new ArrayDeque<>();
            private String pendingDo;
            private OpBuilder current;
            /** The text operator being run, while it runs. */
            private String showing;

            {
                // A text stripper only follows the operators that affect where text lands, so
                // colours and line width would stay at their defaults. They are needed to draw an
                // edited character in the colour and stroke weight it originally had.
                addOperator(new SetStrokingColorSpace(this));
                addOperator(new SetNonStrokingColorSpace(this));
                addOperator(new SetStrokingColor(this));
                addOperator(new SetStrokingColorN(this));
                addOperator(new SetNonStrokingColor(this));
                addOperator(new SetNonStrokingColorN(this));
                addOperator(new SetStrokingDeviceGrayColor(this));
                addOperator(new SetNonStrokingDeviceGrayColor(this));
                addOperator(new SetStrokingDeviceRGBColor(this));
                addOperator(new SetNonStrokingDeviceRGBColor(this));
                addOperator(new SetStrokingDeviceCMYKColor(this));
                addOperator(new SetNonStrokingDeviceCMYKColor(this));
                addOperator(new SetLineWidth(this));
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
                if (!isShowText(name)) {
                    super.processOperator(operator, operands);
                    return;
                }
                if (isQuote(showing)) {
                    // ' moves to the next line and runs a Tj of its own, " sets the spacing and runs a ' of its
                    // own: those are part of it, not more operators of the stream, and the Tj's advance is its
                    // advance (the line move is not).
                    Matrix before = getTextMatrix() == null ? null : getTextMatrix().clone();
                    super.processOperator(operator, operands);
                    if (current != null && OperatorName.SHOW_TEXT.equals(name)) {
                        current.adjust = advance(before, getTextMatrix());
                    }
                    return;
                }
                Frame frame = frames.peek();
                ShowOpRef ref = frame.inert ? null : new ShowOpRef(frame.key, frame.count++);
                OpBuilder builder = ref == null ? null : new OpBuilder(ref, name);
                Matrix before = getTextMatrix() == null ? null : getTextMatrix().clone();
                String outer = showing;
                current = builder;
                showing = name;
                try {
                    super.processOperator(operator, operands);
                } finally {
                    current = null;
                    showing = outer;
                }
                if (builder != null && builder.seen) {
                    if (!isQuote(name)) {
                        builder.adjust = advance(before, getTextMatrix());
                    }
                    builders.put(ref, builder);
                }
            }

            /** The TJ number that stands for the way the operator that just ran moved the text position. */
            private double advance(Matrix before, Matrix after) {
                if (before == null || after == null) {
                    return Double.NaN;
                }
                double det = (double) before.getScaleX() * before.getScaleY()
                        - (double) before.getShearY() * before.getShearX();
                if (Math.abs(det) < 1e-9) {
                    return Double.NaN;
                }
                // The text matrix moved by a translation (tx, ty) in text space: after = T(tx, ty) x before,
                // so T = after x inverse(before); only T's translation part is needed.
                double ia = before.getScaleY() / det;
                double ib = -before.getShearY() / det;
                double ic = -before.getShearX() / det;
                double id = before.getScaleX() / det;
                double ie = ((double) before.getShearX() * before.getTranslateY()
                        - (double) before.getScaleY() * before.getTranslateX()) / det;
                double iff = ((double) before.getShearY() * before.getTranslateX()
                        - (double) before.getScaleX() * before.getTranslateY()) / det;
                double tx = after.getTranslateX() * ia + after.getTranslateY() * ic + ie;
                double ty = after.getTranslateX() * ib + after.getTranslateY() * id + iff;
                PDGraphicsState gs = getGraphicsState();
                double size = gs.getTextState().getFontSize();
                double scale = gs.getTextState().getHorizontalScaling() / 100.0;
                if (size == 0 || scale == 0 || Math.abs(ty) > 1e-3) {
                    return Double.NaN;
                }
                return -tx / (size * scale) * 1000.0;
            }

            @Override
            public void showForm(PDFormXObject form) throws IOException {
                String parent = frames.isEmpty() ? PAGE_STREAM : frames.peek().key;
                boolean inert = !frames.isEmpty() && frames.peek().inert;
                String key = parent + "/" + pendingDo;
                invocations.merge(key, 1, Integer::sum);
                frames.push(new Frame(key, inert));
                try {
                    super.showForm(form);
                } finally {
                    frames.pop();
                }
            }

            @Override
            protected void processType3Stream(PDType3CharProc charProc, Matrix textRenderingMatrix)
                    throws IOException {
                // What a Type 3 glyph draws is not part of the page's own operator sequence.
                frames.push(new Frame("type3", true));
                try {
                    super.processType3Stream(charProc, textRenderingMatrix);
                } finally {
                    frames.pop();
                }
            }

            @Override
            protected void processTextPosition(TextPosition text) {
                // The engine's current graphics state is the one this character is drawn with.
                PDGraphicsState gs = getGraphicsState();
                states.put(text, new DrawState(gs.getTextState().getRenderingMode().intValue(),
                        gs.getLineWidth(), gs.getNonStrokingColor(), gs.getStrokingColor()));
                if (current != null) {
                    opOf.put(text, current.ref);
                    float left = text.getXDirAdj() + originX;
                    if (!current.seen) {
                        current.baseline = pageHeight - text.getYDirAdj() + originY;
                    }
                    current.seen = true;
                    current.minX = Math.min(current.minX, left);
                    current.maxX = Math.max(current.maxX, left + text.getWidthDirAdj());
                    current.size = Math.max(current.size, effectiveFontSize(text, text.getHeightDir()));
                }
                super.processTextPosition(text);
            }

            @Override
            protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
                if (text == null || text.trim().isEmpty() || textPositions.isEmpty()) {
                    return;
                }
                TextPosition first = textPositions.get(0);
                float x = first.getXDirAdj() + originX;
                float right = first.getXDirAdj();
                float fontSize = 0f;
                boolean describable = true;
                List<Glyph> glyphs = new ArrayList<>(textPositions.size());
                Set<ShowOpRef> ops = new LinkedHashSet<>();
                for (TextPosition tp : textPositions) {
                    ops.add(opOf.getOrDefault(tp, ShowOpRef.UNKNOWN));
                    // The run's real extent, not the sum of glyph widths: character spacing adds
                    // room between glyphs that the widths don't include, and a white-out only as
                    // wide as the summed widths would leave the line's last glyph showing.
                    right = Math.max(right, tp.getXDirAdj() + tp.getWidthDirAdj());
                    float size = effectiveFontSize(tp, tp.getHeightDir());
                    fontSize = Math.max(fontSize, size);
                    int[] codes = tp.getCharacterCodes();
                    if (tp.getFont() == null || codes == null || codes.length != 1) {
                        describable = false;
                        continue;
                    }
                    DrawState state = states.get(tp);
                    glyphs.add(new Glyph(tp.getUnicode(), tp.getFont(), codes[0], tp.getXDirAdj() + originX, size,
                            horizontalScale(tp), state != null ? state.renderMode() : 0,
                            state != null ? state.lineWidth() : 1f, state != null ? state.fill() : null,
                            state != null ? state.stroke() : null));
                }
                float width = right - first.getXDirAdj();
                float height = first.getHeightDir();
                float pdfY = pageHeight - first.getYDirAdj() + originY;
                PDFont font = first.getFont();
                String fontName = font != null ? font.getName() : null;
                runs.add(new TextRun(x, pdfY, width, height, fontSize, text, fontName, font,
                        describable ? List.copyOf(glyphs) : List.of()));
                runOps.add(ops);
            }
        };
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.getText(doc);

        Map<ShowOpRef, PageExtraction.ShowOp> ops = new LinkedHashMap<>();
        builders.forEach((ref, b) -> ops.put(ref, new PageExtraction.ShowOp(ref, b.operator, b.adjust, b.minX, b.maxX,
                b.baseline, b.size)));
        Set<String> repeated = new HashSet<>();
        invocations.forEach((key, count) -> {
            if (count > 1) {
                repeated.add(key);
            }
        });
        return new PageExtraction(runs, runOps, ops, repeated);
    }

    private static boolean isShowText(String operator) {
        return OperatorName.SHOW_TEXT.equals(operator) || OperatorName.SHOW_TEXT_ADJUSTED.equals(operator)
                || OperatorName.SHOW_TEXT_LINE.equals(operator) || OperatorName.SHOW_TEXT_LINE_AND_SPACE.equals(operator);
    }

    /** ' or ": a text operator that moves to the next line first. */
    private static boolean isQuote(String operator) {
        return OperatorName.SHOW_TEXT_LINE.equals(operator) || OperatorName.SHOW_TEXT_LINE_AND_SPACE.equals(operator);
    }

    /**
     * {@code getYScale()} is the effective rendering scale (nominal Tf size composed with the
     * text/CTM matrix). For ordinary text it equals {@code getFontSizeInPt()}; for PDF generators
     * that set a large nominal Tf size but compensate with a matrix scale-down it still reflects
     * the glyphs actually on the page, while the nominal size does not. The nominal size and the
     * geometric height are only fallbacks.
     */
    private static float effectiveFontSize(TextPosition tp, float height) {
        float scale = tp.getYScale();
        if (scale > 0.01f) {
            return scale;
        }
        float nominal = tp.getFontSizeInPt();
        if (nominal > 0) {
            return nominal;
        }
        return height > 0 ? height / 0.85f : 10f;
    }

    /** Width of the glyphs relative to their height (horizontal scaling, "장평"); 1 for normal text. */
    private static float horizontalScale(TextPosition tp) {
        float y = tp.getYScale();
        float x = tp.getXScale();
        if (y > 0.01f && x > 0.01f) {
            float ratio = x / y;
            return Math.abs(ratio - 1f) < 0.001f ? 1f : ratio;
        }
        return 1f;
    }
}
