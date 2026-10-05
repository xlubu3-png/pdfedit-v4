package com.pdfedit.dto;

/**
 * A text run as the browser needs it to build an editable overlay. Coordinates are PDF user space
 * (origin bottom-left, y-up); {@code boxBottom}/{@code boxTop} are the same vertical extent the
 * exported white-out rectangle uses, so the on-screen box always matches what gets saved.
 *
 * @param fontFamily     the installed family the run is redrawn in when nothing is overridden
 * @param sourceFont     the font the original page uses, as named in the PDF ("Type3" for glyph-drawn fonts)
 * @param color          "#rrggbb" the run was printed in
 * @param glyphReuse     true when an edit that changes only the text can be drawn with the page's own glyphs
 *                       (the original look is kept); false when the line has to be redrawn in an installed font
 * @param editFontFamily the user's font choice for this run, or null; likewise the other {@code edit*}
 *                       fields, and {@code dx}/{@code dy} for how far the run was moved (points, y up)
 * @param sourceFontMissing the page uses a named font that is not installed on this PC, so characters its
 *                       own (subset) font lacks are drawn in a stand-in; the user can install it
 * @param ownChars       the characters this run's own fonts already draw on the page; a typed character
 *                       outside them cannot take the original glyph and is drawn in an installed font
 */
public record TextRunDto(int index, String text, String currentText, boolean edited,
                         float x, float y, float width, float height, float fontSize,
                         float boxBottom, float boxTop, String fontFamily, boolean bold,
                         String sourceFont, String color, boolean glyphReuse,
                         String editFontFamily, Float editFontSize, Boolean editBold, String editColor,
                         float dx, float dy, String ownChars, boolean sourceFontMissing) {
}
