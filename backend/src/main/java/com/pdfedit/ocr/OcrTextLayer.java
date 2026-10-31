package com.pdfedit.ocr;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;

import com.pdfedit.redact.Redaction;

/**
 * Puts recognized lines on an exported page as invisible text (rendering mode 3, as scanners' "searchable PDF"
 * does): the page looks as scanned, but its words can be searched, selected and copied in any reader.
 */
public final class OcrTextLayer {

    /** Glyph height of the font relative to its size, and how far the baseline sits above the line's bottom. */
    private static final float SIZE_OF_HEIGHT = 0.85f;
    private static final float BASELINE_OF_HEIGHT = 0.2f;

    private OcrTextLayer() {
    }

    /**
     * Draws the lines on the page. A line touching any of the {@code hidden} areas is left out entirely: what a
     * redaction removed must not come back as text.
     *
     * @return how many lines were written
     */
    public static int add(PDDocument doc, PDPage page, List<OcrText> lines, PDFont font, List<Redaction> hidden)
            throws IOException {
        Map<Integer, Boolean> drawable = new HashMap<>();
        int written = 0;
        try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true,
                true)) {
            cs.beginText();
            cs.setRenderingMode(RenderingMode.NEITHER);
            for (OcrText line : lines) {
                if (hidden.stream().anyMatch(r -> line.overlaps(r.x(), r.y(), r.width(), r.height()))) {
                    continue;
                }
                String text = drawableText(line.text(), font, drawable);
                if (text.isBlank() || line.width() <= 0 || line.height() <= 0) {
                    continue;
                }
                // A vertical line is written turned, running down (or up) the page as it does on the scan.
                boolean vertical = line.vertical();
                float across = vertical ? line.width() : line.height();
                float along = vertical ? line.height() : line.width();
                float size = across * SIZE_OF_HEIGHT;
                float natural = font.getStringWidth(text) / 1000f * size;
                if (natural <= 0) {
                    continue;
                }
                // Stretched or squeezed to the length the line has on the scan, so a selection matches the words.
                float scaling = Math.max(10f, Math.min(1000f, 100f * along / natural));
                cs.setFont(font, size);
                cs.setHorizontalScaling(scaling);
                if (!vertical) {
                    cs.setTextMatrix(Matrix.getTranslateInstance(line.x(), line.y() + across * BASELINE_OF_HEIGHT));
                } else if (line.upwards()) {
                    cs.setTextMatrix(Matrix.getRotateInstance(Math.PI / 2,
                            line.x() + across * (1 - BASELINE_OF_HEIGHT), line.y()));
                } else {
                    cs.setTextMatrix(Matrix.getRotateInstance(-Math.PI / 2, line.x() + across * BASELINE_OF_HEIGHT,
                            line.y() + line.height()));
                }
                cs.showText(text);
                written++;
            }
            cs.endText();
        }
        return written;
    }

    /** The text without the characters the font has no glyph for (they would make the whole line fail). */
    private static String drawableText(String text, PDFont font, Map<Integer, Boolean> drawable) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            boolean ok = drawable.computeIfAbsent(cp, c -> {
                try {
                    font.encode(new String(Character.toChars(c)));
                    return true;
                } catch (IOException | IllegalArgumentException e) {
                    return false;
                }
            });
            if (ok) {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }
}
