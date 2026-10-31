package com.pdfedit.ocr;

import java.util.ArrayList;
import java.util.List;

import com.pdfedit.text.AddedText;
import com.pdfedit.text.TextLines;

/**
 * A line of text recognized on a page, placed in the page's PDF user space (points, origin bottom-left, y up).
 *
 * @param y       bottom edge
 * @param score   how sure the recognition was, 0 to 1
 * @param centers for every char of the text, where along the line (0 to 1) it was seen; null for lines kept
 *                before this was recorded, whose letters are then taken as evenly spread
 * @param orientation how a vertical line runs ({@link OcrLine#DOWN}, {@link OcrLine#UP}, {@link OcrLine#STACKED});
 *                null across the page, or for lines kept before this was recorded (then told by their shape)
 */
public record OcrText(String text, float x, float y, float width, float height, float score, List<Float> centers,
        String orientation) {

    public OcrText(String text, float x, float y, float width, float height, float score, List<Float> centers) {
        this(text, x, y, width, height, score, centers, null);
    }

    /** Letters are taken this much wider than half the usual distance between two, so a box covers its letter. */
    private static final float LETTER_SPREAD = 0.6f;

    /** Whether the line touches the rectangle at all. */
    public boolean overlaps(float rx, float ry, float rw, float rh) {
        return x < rx + rw && rx < x + width && y < ry + rh && ry < y + height;
    }

    /** The lines that no text box rewrites: those whose middle is not under the cover of one of the boxes. */
    public static List<OcrText> notRewritten(List<OcrText> lines, List<AddedText> boxes) {
        List<AddedText.Cover> covers = boxes.stream().map(AddedText::cover).filter(c -> c != null).toList();
        if (covers.isEmpty()) {
            return lines;
        }
        return lines.stream()
                .filter(l -> covers.stream().noneMatch(c -> c.contains(l.x + l.width / 2, l.y + l.height / 2)))
                .toList();
    }

    /** Whether the line runs down the page (letters one under the other, or a line turned a quarter). */
    public boolean vertical() {
        return orientation != null || height >= OcrEngine.VERTICAL * width;
    }

    /** Whether a vertical line reads from the bottom up (a line turned a quarter to the left). */
    public boolean upwards() {
        if (orientation != null) {
            return OcrLine.UP.equals(orientation);
        }
        return vertical() && centers != null && centers.size() >= 2 && centers.getLast() < centers.getFirst();
    }

    /** Whether a vertical line is a line turned a quarter (not upright letters one under the other). */
    public boolean turned() {
        return OcrLine.DOWN.equals(orientation) || OcrLine.UP.equals(orientation);
    }

    /**
     * Where every letter was seen, as a share of the line from its left end - or for a vertical line from its
     * top; evenly spread when that was not recorded.
     */
    public float[] letterPlaces() {
        int n = text.length();
        float[] at = new float[n];
        for (int i = 0; i < n; i++) {
            at[i] = centers != null && centers.size() == n ? centers.get(i) : (i + 0.5f) / n;
        }
        return at;
    }

    /**
     * The line with an area for every letter, so a word of it can be pointed at (to redact a number found in
     * it, for instance). The letters nearest the ends of the line reach them; on a vertical line the letters lie
     * one under the other.
     */
    public TextLines.Line line() {
        int n = text.length();
        float[] at = letterPlaces();
        boolean down = vertical();
        float length = down ? height : width;
        List<Float> gaps = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            if (!Character.isWhitespace(text.charAt(i)) && !Character.isWhitespace(text.charAt(i - 1))) {
                gaps.add(Math.abs(at[i] - at[i - 1]) * length);
            }
        }
        gaps.sort(null);
        float half = gaps.isEmpty() ? length / Math.max(n, 1) / 2 : gaps.get(gaps.size() / 2) * LETTER_SPREAD;
        int first = 0;
        int last = 0;
        for (int i = 0; i < n; i++) {
            if (at[i] < at[first]) {
                first = i;
            }
            if (at[i] >= at[last]) {
                last = i;
            }
        }
        List<TextLines.Area> boxes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                boxes.add(null);
                continue;
            }
            // From the line's start (its left end, or its top) along it.
            float from = i == first ? 0 : Math.max(0, at[i] * length - half);
            float to = i == last ? length : Math.min(length, at[i] * length + half);
            float size = Math.max(to - from, 0.1f);
            boxes.add(down ? new TextLines.Area(x, y + height - from - size, width, size)
                    : new TextLines.Area(x + from, y, size, height));
        }
        return TextLines.Line.of(text, boxes);
    }
}
