package com.pdfedit.ocr;

/**
 * A line of text read from a picture.
 *
 * @param text    what it says
 * @param score   how sure the reader is, 0 to 1 (the mean of its letters' probabilities)
 * @param corners the line's four corners in picture pixels, top-left, top-right, bottom-right, bottom-left,
 *                as x0, y0, x1, y1, ... (the line can be slightly turned on a tilted scan)
 * @param centers for every char of the text, where along the line (0 at its left end, 1 at its right; on a
 *                vertical line 0 at its top, 1 at its bottom) the reader saw it: what it takes to point at a word
 *                on the page
 * @param orientation how a vertical line runs: {@link #DOWN}, {@link #UP} or {@link #STACKED}; null for a line
 *                across the page
 */
public record OcrLine(String text, float score, float[] corners, float[] centers, String orientation) {

    /** A vertical line read downwards: turned a quarter to the right on the page. */
    public static final String DOWN = "down";
    /** A vertical line read upwards: turned a quarter to the left. */
    public static final String UP = "up";
    /** Upright letters one under the other. */
    public static final String STACKED = "stacked";

    public float left() {
        return Math.min(corners[0], corners[6]);
    }

    public float right() {
        return Math.max(corners[2], corners[4]);
    }

    public float top() {
        return Math.min(corners[1], corners[3]);
    }

    public float bottom() {
        return Math.max(corners[5], corners[7]);
    }
}
