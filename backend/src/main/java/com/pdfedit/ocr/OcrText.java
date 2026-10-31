package com.pdfedit.ocr;

import java.util.ArrayList;
import java.util.List;

import com.pdfedit.text.TextLines;

/**
 * A line of text recognized on a page, placed in the page's PDF user space (points, origin bottom-left, y up).
 *
 * @param y       bottom edge
 * @param score   how sure the recognition was, 0 to 1
 * @param centers for every char of the text, where along the line (0 to 1) it was seen; null for lines kept
 *                before this was recorded, whose letters are then taken as evenly spread
 */
public record OcrText(String text, float x, float y, float width, float height, float score, List<Float> centers) {

    /** Letters are taken this much wider than half the usual distance between two, so a box covers its letter. */
    private static final float LETTER_SPREAD = 0.6f;

    /** Whether the line touches the rectangle at all. */
    public boolean overlaps(float rx, float ry, float rw, float rh) {
        return x < rx + rw && rx < x + width && y < ry + rh && ry < y + height;
    }

    /**
     * The line with an area for every letter, so a word of it can be pointed at (to redact a number found in
     * it, for instance). The first and last letters reach the ends of the line.
     */
    public TextLines.Line line() {
        int n = text.length();
        float[] at = new float[n];
        for (int i = 0; i < n; i++) {
            at[i] = centers != null && centers.size() == n ? centers.get(i) : (i + 0.5f) / n;
        }
        List<Float> gaps = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            if (!Character.isWhitespace(text.charAt(i)) && !Character.isWhitespace(text.charAt(i - 1))) {
                gaps.add((at[i] - at[i - 1]) * width);
            }
        }
        gaps.sort(null);
        float half = gaps.isEmpty() ? width / Math.max(n, 1) / 2 : gaps.get(gaps.size() / 2) * LETTER_SPREAD;
        List<TextLines.Area> boxes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                boxes.add(null);
                continue;
            }
            float cx = x + at[i] * width;
            float left = i == 0 ? x : Math.max(x, cx - half);
            float right = i == n - 1 ? x + width : Math.min(x + width, cx + half);
            boxes.add(new TextLines.Area(left, y, Math.max(right - left, 0.1f), height));
        }
        return TextLines.Line.of(text, boxes);
    }
}
