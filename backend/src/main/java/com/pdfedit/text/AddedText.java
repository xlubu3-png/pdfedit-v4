package com.pdfedit.text;

/**
 * A text box the user put on a page. Unlike a {@link RunEdit} it does not replace anything of the
 * original page: it is simply drawn on top.
 *
 * @param id         chosen by the browser, only to tell boxes apart
 * @param text       may contain line breaks
 * @param x          left edge, PDF user space (origin bottom-left, y-up), in points
 * @param y          baseline of the first line
 * @param fontFamily family name as listed by {@code GET /fonts}; null for the default (Malgun Gothic)
 * @param fontSize   in points
 * @param color      "#rrggbb"; null for black
 */
public record AddedText(String id, String text, float x, float y, String fontFamily, float fontSize,
                        boolean bold, String color) {
}
