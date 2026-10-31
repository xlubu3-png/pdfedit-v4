package com.pdfedit.ocr;

/**
 * A line of text recognized on a page, placed in the page's PDF user space (points, origin bottom-left, y up).
 *
 * @param y     bottom edge
 * @param score how sure the recognition was, 0 to 1
 */
public record OcrText(String text, float x, float y, float width, float height, float score) {

    /** Whether the line touches the rectangle at all. */
    public boolean overlaps(float rx, float ry, float rw, float rh) {
        return x < rx + rw && rx < x + width && y < ry + rh && ry < y + height;
    }
}
