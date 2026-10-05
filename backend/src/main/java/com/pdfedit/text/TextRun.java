package com.pdfedit.text;

import java.util.List;

import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * A text run positioned in PDF user space (origin bottom-left, y-up), matching
 * {@code PDPageContentStream.newLineAtOffset} conventions so it can be redrawn directly.
 *
 * @param fontSize       the largest effective font size among the run's characters
 * @param sourceFontName base name PDFBox reported for the run's first font (e.g. "INPILL+HCRBatang-Bold"), or null
 * @param sourceFont     the first character's font object, or null. Only valid while the
 *                       {@code PDDocument} it was extracted from is open, so edits persisted between
 *                       requests never carry one.
 * @param glyphs         every character of the run with the font, size, colour and position it was drawn
 *                       with; empty for stored edits and when the source characters can't be described
 *                       one by one
 */
public record TextRun(float x, float y, float width, float height, float fontSize, String text,
                      String sourceFontName, PDFont sourceFont, List<Glyph> glyphs) {

    public TextRun(float x, float y, float width, float height, float fontSize, String text,
                   String sourceFontName) {
        this(x, y, width, height, fontSize, text, sourceFontName, null, List.of());
    }
}
