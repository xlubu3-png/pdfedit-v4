package com.pdfedit.text;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;

/**
 * One character as the source PDF drew it - everything needed to draw it again identically.
 *
 * @param unicode    the character as text
 * @param font       the PDF's own font object for this character (not a copy)
 * @param code       the character code inside that font, or -1 if unknown
 * @param x          left edge in user space
 * @param size       effective font size in points (nominal size composed with the text matrix)
 * @param hScale     horizontal scale relative to the vertical scale (1 = normal width)
 * @param renderMode text rendering mode: 0 fill, 1 stroke, 2 fill and stroke (a common faux bold)...
 * @param lineWidth  stroke width used by the stroking render modes
 * @param fill       fill colour
 * @param stroke     stroke colour
 */
public record Glyph(String unicode, PDFont font, int code, float x, float size, float hScale,
                    int renderMode, float lineWidth, PDColor fill, PDColor stroke) {
}
