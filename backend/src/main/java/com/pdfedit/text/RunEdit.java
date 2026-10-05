package com.pdfedit.text;

import java.awt.Color;

/**
 * What the user did to one text run of a page: the new text, optionally a different font, size,
 * weight or colour, and optionally a move. A null override means "as the original".
 *
 * @param fontFamily family name as listed by {@code GET /fonts}, or null for the run's own font
 * @param fontSize   size in points, or null for the original size
 * @param bold       true/false to force the weight, or null for the original
 * @param color      "#rrggbb", or null for the colour the run was printed in
 * @param dx         how far the run moved right, in points (PDF user space)
 * @param dy         how far the run moved up, in points; negative is down
 */
public record RunEdit(String text, String fontFamily, Float fontSize, Boolean bold, String color,
                      float dx, float dy) {

    public static RunEdit ofText(String text) {
        return new RunEdit(text, null, null, null, null, 0f, 0f);
    }

    /** True when the font, size, weight or colour differ from the original: the run can't be redrawn glyph by glyph then. */
    public boolean restyled() {
        return fontFamily != null || fontSize != null || bold != null || color != null;
    }

    public boolean moved() {
        return dx != 0f || dy != 0f;
    }

    /** "#rrggbb" to a colour; {@code fallback} when the text is missing or not in that form. */
    public static Color colour(String hex, Color fallback) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}")) {
            return fallback;
        }
        return new Color(Integer.parseInt(hex.substring(1), 16));
    }

    public static String hex(Color colour) {
        return String.format("#%06x", colour.getRGB() & 0xFFFFFF);
    }
}
