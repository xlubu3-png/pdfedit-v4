package com.pdfedit.text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Extracts per-run text positions from a single PDF page, converted into PDF user space
 * (origin bottom-left, y-up) so results line up directly with {@code PDPageContentStream}
 * drawing calls. Each run also records how every one of its characters was drawn.
 */
public final class TextExtraction {

    private TextExtraction() {
    }

    /** Graphics state in effect when a character was drawn. */
    private record DrawState(int renderMode, float lineWidth, PDColor fill, PDColor stroke) {
    }

    public static List<TextRun> extractRuns(PDDocument doc, int pageIndex) throws IOException {
        List<TextRun> runs = new ArrayList<>();
        PDPage page = doc.getPage(pageIndex);
        // PDFTextStripper reports positions relative to the crop box's lower-left corner.
        PDRectangle crop = page.getCropBox();
        float originX = crop.getLowerLeftX();
        float originY = crop.getLowerLeftY();
        float pageHeight = crop.getHeight();
        Map<TextPosition, DrawState> states = new IdentityHashMap<>();

        PDFTextStripper stripper = new PDFTextStripper() {
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
            protected void processTextPosition(TextPosition text) {
                // The engine's current graphics state is the one this character is drawn with.
                PDGraphicsState gs = getGraphicsState();
                states.put(text, new DrawState(gs.getTextState().getRenderingMode().intValue(),
                        gs.getLineWidth(), gs.getNonStrokingColor(), gs.getStrokingColor()));
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
                for (TextPosition tp : textPositions) {
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
            }
        };
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.getText(doc);
        return runs;
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
