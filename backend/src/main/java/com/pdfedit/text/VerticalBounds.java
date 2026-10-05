package com.pdfedit.text;

import java.util.List;

/**
 * Computes the white-out/overlay box's vertical extent for a text run: generous enough to cover
 * ascenders/descenders for any font (driven by fontSize, since per-glyph height metrics are
 * unreliable - see {@link TextExtraction}), but clamped to the actual gap to the nearest row
 * above/below in the same column, so tightly packed table rows don't get overlapping boxes.
 */
public final class VerticalBounds {

    private VerticalBounds() {
    }

    /** Returns {bottom, top} in PDF user-space y coordinates. */
    public static float[] compute(List<TextRun> allRunsOnPage, TextRun target) {
        float defaultBottom = target.y() - target.fontSize() * 0.35f;
        float defaultTop = target.y() + target.fontSize() * 1.05f;

        float nearestAboveY = Float.POSITIVE_INFINITY;
        float nearestBelowY = Float.NEGATIVE_INFINITY;

        for (TextRun r : allRunsOnPage) {
            if (r == target) {
                continue;
            }
            boolean xOverlaps = r.x() < target.x() + target.width() && r.x() + r.width() > target.x();
            if (!xOverlaps) {
                continue;
            }
            if (r.y() > target.y() && r.y() < nearestAboveY) {
                nearestAboveY = r.y();
            }
            if (r.y() < target.y() && r.y() > nearestBelowY) {
                nearestBelowY = r.y();
            }
        }

        // Each side may claim at most 45% of the gap to its neighbor: the neighbor computes its own
        // clamp independently against the same gap, so two sides each allowed past 50% would
        // overlap. But the clamp is floored by a fontSize-only minimum so a box is never starved
        // below what a real glyph needs (a Hangul syllable's top sliver left uncovered blends with
        // the glyph underneath and looks like a corrupted character). In extremely dense tables
        // this can cause a slight overlap, which is a far smaller defect than a mangled glyph.
        float minTopExtent = target.fontSize() * 0.9f;
        float minBottomExtent = target.fontSize() * 0.3f;

        float top = defaultTop;
        if (nearestAboveY != Float.POSITIVE_INFINITY) {
            float gapClampTop = target.y() + (nearestAboveY - target.y()) * 0.45f;
            top = Math.min(defaultTop, Math.max(gapClampTop, target.y() + minTopExtent));
        }
        float bottom = defaultBottom;
        if (nearestBelowY != Float.NEGATIVE_INFINITY) {
            float gapClampBottom = target.y() - (target.y() - nearestBelowY) * 0.45f;
            bottom = Math.max(defaultBottom, Math.min(gapClampBottom, target.y() - minBottomExtent));
        }

        if (top <= bottom) {
            // Rows closer together than the font itself: minimal sliver around the baseline
            // rather than an inverted/zero-height box.
            top = target.y() + target.fontSize() * 0.3f;
            bottom = target.y() - target.fontSize() * 0.1f;
        }
        return new float[] {bottom, top};
    }
}
