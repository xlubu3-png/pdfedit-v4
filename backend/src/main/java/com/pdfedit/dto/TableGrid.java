package com.pdfedit.dto;

import java.util.List;

/**
 * A table the user set out by hand on a page (표 칸 바로잡기): where its column and row borders are, in points from
 * the top-left corner of the page's visible area, left to right and top to bottom. The first and last of each are
 * its edges. Text in it goes into its cells when the page is saved to Excel or copied as a table, instead of into
 * the tables found on the page by themselves.
 */
public record TableGrid(List<Double> xs, List<Double> ys, List<Merge> merges) {

    /** Most borders a table may have across or down, and most merged cells. */
    public static final int MAX_LINES = 200;
    public static final int MAX_MERGES = 500;

    /** Cells merged into one (합치기): the area they cover, points from the page's top-left. */
    public record Merge(double x0, double y0, double x1, double y1) {
        boolean valid() {
            return Double.isFinite(x0) && Double.isFinite(y0) && Double.isFinite(x1) && Double.isFinite(y1)
                    && x1 > x0 && y1 > y0;
        }
    }

    public TableGrid(List<Double> xs, List<Double> ys) {
        this(xs, ys, null);
    }

    /** Whether the borders make a table: 2 to {@value #MAX_LINES} each way, in order, finite, on the page. */
    public boolean valid() {
        return ordered(xs) && ordered(ys)
                && (merges == null || merges.size() <= MAX_MERGES && merges.stream().allMatch(m -> m != null && m.valid()));
    }

    private static boolean ordered(List<Double> values) {
        if (values == null || values.size() < 2 || values.size() > MAX_LINES) {
            return false;
        }
        double last = Double.NEGATIVE_INFINITY;
        for (Double v : values) {
            if (v == null || !Double.isFinite(v) || v < -1 || v > 20_000 || v <= last) {
                return false;
            }
            last = v;
        }
        return true;
    }
}
