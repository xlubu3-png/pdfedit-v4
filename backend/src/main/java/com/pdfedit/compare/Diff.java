package com.pdfedit.compare;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The places where two sequences differ, by Myers' algorithm (the shortest edit script, as {@code diff}
 * finds it). The work grows with the number of differences, not with the length, so two long documents
 * that differ in a few places are compared quickly; past {@code maxEdits} differences the remaining middle
 * is reported as one changed stretch instead.
 */
final class Diff {

    private Diff() {
    }

    /** {@code a[aFrom, aTo)} was replaced by {@code b[bFrom, bTo)}; either side may be empty. */
    record Hunk(int aFrom, int aTo, int bFrom, int bTo) {
    }

    static List<Hunk> hunks(List<String> a, List<String> b, int maxEdits) {
        int n = a.size();
        int m = b.size();
        // What both share at the start and at the end needs no search.
        int start = 0;
        while (start < n && start < m && a.get(start).equals(b.get(start))) {
            start++;
        }
        int endA = n;
        int endB = m;
        while (endA > start && endB > start && a.get(endA - 1).equals(b.get(endB - 1))) {
            endA--;
            endB--;
        }
        List<Hunk> hunks = new ArrayList<>();
        if (start == endA && start == endB) {
            return hunks;
        }
        if (start == endA || start == endB) {
            hunks.add(new Hunk(start, endA, start, endB));
            return hunks;
        }
        List<int[]> pairs = matches(a.subList(start, endA), b.subList(start, endB), maxEdits);
        if (pairs == null) {
            hunks.add(new Hunk(start, endA, start, endB));
            return hunks;
        }
        // The stretches between two matched items are what changed.
        int x = 0;
        int y = 0;
        pairs.add(new int[] {endA - start, endB - start});
        for (int[] pair : pairs) {
            if (pair[0] > x || pair[1] > y) {
                hunks.add(new Hunk(start + x, start + pair[0], start + y, start + pair[1]));
            }
            x = pair[0] + 1;
            y = pair[1] + 1;
        }
        return hunks;
    }

    /** The items matched in both, as (index in a, index in b) in increasing order; null past maxEdits. */
    private static List<int[]> matches(List<String> a, List<String> b, int maxEdits) {
        int n = a.size();
        int m = b.size();
        int max = n + m;
        int limit = Math.min(max, maxEdits);
        int offset = max + 1;
        int[] v = new int[2 * max + 3];
        List<int[]> trace = new ArrayList<>();
        for (int d = 0; d <= limit; d++) {
            // v for diagonals -d-1 .. d+1 as it was before this round: what the way back needs.
            trace.add(Arrays.copyOfRange(v, offset - d - 1, offset + d + 2));
            for (int k = -d; k <= d; k += 2) {
                int x = k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])
                        ? v[offset + k + 1] : v[offset + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    return backtrack(trace, n, m);
                }
            }
        }
        return null;
    }

    private static List<int[]> backtrack(List<int[]> trace, int n, int m) {
        List<int[]> pairs = new ArrayList<>();
        int x = n;
        int y = m;
        for (int d = trace.size() - 1; d >= 0 && (x > 0 || y > 0); d--) {
            int[] v = trace.get(d);
            int k = x - y;
            int prevK = k == -d || (k != d && v[k - 1 + d + 1] < v[k + 1 + d + 1]) ? k + 1 : k - 1;
            int prevX = v[prevK + d + 1];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                x--;
                y--;
                pairs.add(new int[] {x, y});
            }
            x = prevX;
            y = prevY;
        }
        Collections.reverse(pairs);
        return pairs;
    }
}
