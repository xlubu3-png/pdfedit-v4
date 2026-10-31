package com.pdfedit.text;

import java.util.List;

/**
 * A text box the user put on a page. Unlike a {@link RunEdit} it does not replace anything of the
 * original page: it is simply drawn on top - over its {@code cover}, when it has one.
 *
 * @param id         chosen by the browser, only to tell boxes apart
 * @param text       may contain line breaks
 * @param x          left edge, PDF user space (origin bottom-left, y-up), in points
 * @param y          baseline of the first line
 * @param fontFamily family name as listed by {@code GET /fonts}; null for the default (Malgun Gothic)
 * @param fontSize   in points
 * @param color      "#rrggbb"; null for black
 * @param spans      parts of the text with a look of their own (bold, colour, size, underline), as character
 *                   positions in {@code text} (line breaks counted); never null
 * @param cover      an area painted over before the text is drawn, to hide the letters of a scan the box
 *                   rewrites; null for none. It stays where it is when the box is moved.
 * @param rotation   degrees the text is turned about (x, y), counter-clockwise as PDF counts: -90 runs down
 *                   the page, 90 up it; 0 across, as boxes usually are (also when not given: a box made in the
 *                   browser leaves it out)
 * @param charSpacing points added after every letter (negative: letters closer), as the PDF's character spacing;
 *                   0 when not given. Rewritten scan lines get it so they take the line's length on the scan.
 */
public record AddedText(String id, String text, float x, float y, String fontFamily, float fontSize,
                        boolean bold, String color, List<RunEdit.Span> spans, Cover cover, Float rotation,
                        Float charSpacing) {

    /**
     * A rectangle in PDF user space painted over: with {@code patch}, by that picture stretched over it; without,
     * filled with {@code color}.
     *
     * @param y     bottom edge
     * @param color "#rrggbb", the paper's colour
     * @param patch a PNG (base64) in the paper's colour where the scanned letters are and see-through elsewhere,
     *              so the table rules and shading around them stay; null for a plain fill
     */
    public record Cover(float x, float y, float width, float height, String color, String patch) {

        public Cover(float x, float y, float width, float height, String color) {
            this(x, y, width, height, color, null);
        }

        /** Whether the point lies inside. */
        public boolean contains(float px, float py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }

    public AddedText {
        spans = spans == null ? List.of() : List.copyOf(spans);
        rotation = rotation == null ? 0f : rotation;
        charSpacing = charSpacing == null ? 0f : charSpacing;
    }

    public AddedText(String id, String text, float x, float y, String fontFamily, float fontSize,
                     boolean bold, String color, List<RunEdit.Span> spans, Cover cover, Float rotation) {
        this(id, text, x, y, fontFamily, fontSize, bold, color, spans, cover, rotation, 0f);
    }

    public AddedText(String id, String text, float x, float y, String fontFamily, float fontSize,
                     boolean bold, String color, List<RunEdit.Span> spans, Cover cover) {
        this(id, text, x, y, fontFamily, fontSize, bold, color, spans, cover, 0f, 0f);
    }

    public AddedText(String id, String text, float x, float y, String fontFamily, float fontSize,
                     boolean bold, String color, List<RunEdit.Span> spans) {
        this(id, text, x, y, fontFamily, fontSize, bold, color, spans, null, 0f, 0f);
    }

    public AddedText(String id, String text, float x, float y, String fontFamily, float fontSize,
                     boolean bold, String color) {
        this(id, text, x, y, fontFamily, fontSize, bold, color, List.of(), null, 0f, 0f);
    }

    /** The same box saying {@code newText}, its bold and coloured parts kept on the letters they were on. */
    public AddedText withText(String newText) {
        List<RunEdit.Span> kept = new RunEdit(text, null, null, null, null, 0f, 0f, spans).withText(newText).spans();
        return new AddedText(id, newText, x, y, fontFamily, fontSize, bold, color, kept, cover, rotation, charSpacing);
    }
}
