package com.pdfedit.ocr;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * The check boxes of a scanned or photographed form (□), found in a line's picture, and what recognition read off
 * them put right: the reader leaves out an empty box or reads a ticked one as a letter (V, N, √, ㄷ, 口 ...). A box
 * ticked becomes ☑, an empty one □ (kordoc's ocr check boxes, #243, MIT).
 * <p>
 * A box's sides are thin lines: pixels clearly darker than the paper a few pixels beside them (ridges), so a light
 * printed frame is found where one threshold for ink would break it, and the inside of a bold letter's stroke - dark
 * on both sides too - is not. All four sides must be there, and its corners filled (a round bullet ○ has none), and
 * at least three sides clear lines by themselves (letters' strokes making the shape of a box by chance are not).
 * A tick inside covers at least 6% of the inside and a third of its width; a box nearly full (■) is not a check box.
 */
final class CheckBoxes {

    /** A box: its left and right edge in the line's picture (pixels) and whether something is marked inside. */
    record Box(int x0, int x1, boolean checked) {
    }

    /** What the reader makes of a check box, read as if it were a letter. */
    private static final Set<String> MISREAD = Set.of("V", "v", "N", "M", "C", "√", "✓", "✔", "ㄷ", "ㅁ", "口", "ロ", "□",
            "☐", "☑", "▣", "【", "】");

    private CheckBoxes() {
    }

    /** The check boxes in a line's picture, left to right. */
    static List<Box> find(BufferedImage line) {
        int w = line.getWidth();
        int h = line.getHeight();
        if (h < 12 || w < h) {
            return List.of();
        }
        int[] gray = new int[w * h];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            line.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                gray[y * w + x] = (((p >> 16) & 0xff) * 299 + ((p >> 8) & 0xff) * 587 + (p & 0xff) * 114) / 1000;
            }
        }
        int[] sorted = gray.clone();
        Arrays.sort(sorted);
        int bg = sorted[sorted.length / 2];
        int threshold = bg - 40;
        long sum = 0;
        int n = 0;
        for (int v : gray) {
            if (v <= threshold) {
                sum += v;
                n++;
            }
        }
        if (n == 0) {
            return List.of();
        }
        double dk = (double) sum / n;
        if (bg - dk < 40) {
            return List.of();
        }
        int k = Math.max(2, Math.round(h * 0.05f));
        double delta = Math.max(10, (bg - dk) * 0.12);
        double mark = bg - (bg - dk) * 0.5;
        boolean[] vr = new boolean[w * h];
        boolean[] hr = new boolean[w * h];
        boolean[] dark = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = gray[y * w + x];
                vr[y * w + x] = v < Math.min(at(gray, w, h, x - k, y), at(gray, w, h, x + k, y)) - delta;
                hr[y * w + x] = v < Math.min(at(gray, w, h, x, y - k), at(gray, w, h, x, y + k)) - delta;
                dark[y * w + x] = v < mark;
            }
        }
        double sMin = Math.max(8, h * 0.15);
        double sMax = h;
        // Upright strokes: in each column the longest run of ridges (a column either side counts too).
        List<int[]> strokes = new ArrayList<>(); // {x, top, bottom}
        for (int x = 0; x < w; x++) {
            int best = 0;
            int bestTop = 0;
            int start = -1;
            for (int y = 0; y <= h; y++) {
                boolean on = y < h && (on(vr, w, h, x - 1, y) || on(vr, w, h, x, y) || on(vr, w, h, x + 1, y));
                if (on && start < 0) {
                    start = y;
                } else if (!on && start >= 0) {
                    if (y - start > best) {
                        best = y - start;
                        bestTop = start;
                    }
                    start = -1;
                }
            }
            if (best < sMin * 0.8) {
                continue;
            }
            int[] last = strokes.isEmpty() ? null : strokes.getLast();
            if (last != null && x - last[3] <= 1) {
                last[3] = x; // the stroke's columns run on
                if (best > last[2] - last[1]) {
                    last[0] = x;
                    last[1] = bestTop;
                    last[2] = bestTop + best - 1;
                }
            } else {
                strokes.add(new int[] {x, bestTop, bestTop + best - 1, x});
            }
        }
        List<Box> found = new ArrayList<>();
        for (int i = 0; i < strokes.size(); i++) {
            for (int j = i + 1; j < strokes.size(); j++) {
                int lx = strokes.get(i)[0];
                int rx = strokes.get(j)[0];
                int d = rx - lx;
                if (d < sMin || d > sMax) {
                    continue;
                }
                int y0 = Math.max(0, Math.min(strokes.get(i)[1], strokes.get(j)[1]));
                int y1 = Math.min(h - 1, y0 + d);
                if (y1 - y0 < d * 0.8) {
                    continue;
                }
                Box box = tryBox(vr, hr, dark, w, h, lx, rx, y0, y1);
                if (box != null && found.stream().noneMatch(b -> b.x1() >= box.x0() && b.x0() <= box.x1())) {
                    found.add(box);
                }
            }
        }
        found.sort((a, b) -> Integer.compare(a.x0(), b.x0()));
        return found;
    }

    private static Box tryBox(boolean[] vr, boolean[] hr, boolean[] dark, int w, int h, int lx, int rx, int y0, int y1) {
        int d = rx - lx;
        double left = side(vr, dark, w, h, lx, y0, y1, true, true);
        double right = side(vr, dark, w, h, rx, y0, y1, true, true);
        double top = bestRow(hr, dark, w, h, lx, rx, y0, true);
        double bottom = bestRow(hr, dark, w, h, lx, rx, y1, true);
        if (left < 0.75 || right < 0.75 || top < 0.75 || bottom < 0.75) {
            return null;
        }
        // Clear lines by themselves on at least three sides.
        int clear = 0;
        clear += side(vr, null, w, h, lx, y0, y1, true, false) >= 0.5 ? 1 : 0;
        clear += side(vr, null, w, h, rx, y0, y1, true, false) >= 0.5 ? 1 : 0;
        clear += bestRow(hr, null, w, h, lx, rx, y0, false) >= 0.5 ? 1 : 0;
        clear += bestRow(hr, null, w, h, lx, rx, y1, false) >= 0.5 ? 1 : 0;
        if (clear < 3) {
            return null;
        }
        // Square corners: a round ○ leaves them empty.
        int cw = Math.max(1, Math.round(d * 0.1f));
        int corners = 0;
        int[][] at = {{lx, y0, 1, 1}, {rx, y0, -1, 1}, {lx, y1, 1, -1}, {rx, y1, -1, -1}};
        for (int[] c : at) {
            search:
            for (int a = 0; a < cw + 1; a++) {
                for (int b = 0; b < cw + 1; b++) {
                    int x = c[0] + c[2] * a;
                    int y = c[1] + c[3] * b;
                    if (on(vr, w, h, x, y) || on(hr, w, h, x, y) || on(dark, w, h, x, y)) {
                        corners++;
                        break search;
                    }
                }
            }
        }
        // The inside: what is marked there.
        int m = Math.max(2, Math.round(d * 0.2f));
        int ix0 = lx + m;
        int ix1 = rx - m;
        int iy0 = y0 + m;
        int iy1 = y1 - m;
        if (ix1 <= ix0 || iy1 <= iy0) {
            return null;
        }
        int marked = 0;
        int all = 0;
        int mx0 = Integer.MAX_VALUE;
        int mx1 = -1;
        for (int y = iy0; y <= iy1; y++) {
            for (int x = ix0; x <= ix1; x++) {
                all++;
                if (dark[y * w + x]) {
                    marked++;
                    mx0 = Math.min(mx0, x);
                    mx1 = Math.max(mx1, x);
                }
            }
        }
        double fill = (double) marked / all;
        if (fill >= 0.6) {
            return null; // ■: a filled square, not a box to tick
        }
        boolean checked = fill >= 0.06 && mx1 - mx0 + 1 >= (ix1 - ix0 + 1) * 0.35;
        if (corners < (checked ? 2 : 3)) {
            return null;
        }
        // A box stands apart: paper on both sides of it (a quarter of its size). Strokes of letters making the shape
        // of a box by chance have the rest of their letters right beside them.
        int beside = Math.max(2, Math.round(d * 0.25f));
        if (!blank(dark, w, h, lx - 2 - beside, lx - 3, y0, y1) || !blank(dark, w, h, rx + 3, rx + 2 + beside, y0, y1)) {
            return null;
        }
        return new Box(lx, rx, checked);
    }

    /** How much of an upright side (x, from y0 to y1) is a ridge (or, when {@code dark} is given, marked over). */
    private static double side(boolean[] ridge, boolean[] dark, int w, int h, int x, int y0, int y1, boolean upright, boolean any) {
        int covered = 0;
        for (int y = y0; y <= y1; y++) {
            boolean on = false;
            for (int dx = -1; dx <= 1 && !on; dx++) {
                for (int dy = -1; dy <= 1 && !on; dy++) {
                    on = on(ridge, w, h, x + dx, y + dy) || (any && dark != null && on(dark, w, h, x + dx, y + dy));
                }
            }
            covered += on ? 1 : 0;
        }
        return (double) covered / (y1 - y0 + 1);
    }

    /** How much of a side across (from x0 to x1, at row y give or take two) is a ridge (or marked over). */
    private static double bestRow(boolean[] ridge, boolean[] dark, int w, int h, int x0, int x1, int y, boolean any) {
        double best = 0;
        for (int dy = -2; dy <= 2; dy++) {
            int covered = 0;
            for (int x = x0; x <= x1; x++) {
                boolean on = false;
                for (int dx = -1; dx <= 1 && !on; dx++) {
                    on = on(ridge, w, h, x + dx, y + dy) || (any && dark != null && on(dark, w, h, x + dx, y + dy));
                }
                covered += on ? 1 : 0;
            }
            best = Math.max(best, (double) covered / (x1 - x0 + 1));
        }
        return best;
    }

    /** Whether the strip from x0 to x1 (rows y0 to y1) is paper: next to no ink (the edge of the line counts as paper). */
    private static boolean blank(boolean[] dark, int w, int h, int x0, int x1, int y0, int y1) {
        int inked = 0;
        int all = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
                all++;
                inked += dark[y * w + x] ? 1 : 0;
            }
        }
        return all == 0 || inked <= all * 0.05;
    }

    private static boolean on(boolean[] map, int w, int h, int x, int y) {
        return x >= 0 && x < w && y >= 0 && y < h && map[y * w + x];
    }

    private static int at(int[] gray, int w, int h, int x, int y) {
        return gray[Math.min(h - 1, Math.max(0, y)) * w + Math.min(w - 1, Math.max(0, x))];
    }

    /**
     * What was read off a line put right with the check boxes found in its picture: the letters read where a box is
     * (their places along the line, 0 to 1, in {@code centers}) become ☑ or □ when they are all what a box is misread
     * as and a label follows; a box nothing was read at, with nothing either side of it, is put in before the text
     * after it (a ticked one too: kordoc puts in only empty ones, but a tick is what a form is read for). A box whose place holds Hangul, or letters or digits of a word, is left as read.
     *
     * @return the text and its centers put right, or null when nothing changed
     */
    static Object[] restore(String text, float[] centers, List<Box> boxes, int width) {
        if (boxes.isEmpty() || centers == null || centers.length != text.length() || width <= 0) {
            return null;
        }
        StringBuilder out = new StringBuilder(text);
        List<Float> at = new ArrayList<>();
        for (float c : centers) {
            at.add(c);
        }
        boolean changed = false;
        // From the right, so the places of the boxes still to come stay where they were.
        for (int b = boxes.size() - 1; b >= 0; b--) {
            Box box = boxes.get(b);
            float from = (float) box.x0() / width;
            float to = (float) (box.x1() + 1) / width;
            int first = -1;
            int last = -1;
            for (int i = 0; i < out.length(); i++) {
                if (out.charAt(i) != ' ' && at.get(i) >= from && at.get(i) <= to) {
                    if (first < 0) {
                        first = i;
                    }
                    last = i;
                }
            }
            String mark = box.checked() ? "☑" : "□";
            if (first >= 0) {
                String inBox = out.substring(first, last + 1).replace(" ", "");
                boolean misread = inBox.codePoints().allMatch(cp -> MISREAD.contains(new String(Character.toChars(cp))));
                // A box stands at the head of the line or after a space or an opening bracket ("km/L" is a unit).
                boolean before = first > 0 && out.charAt(first - 1) != ' ' && out.charAt(first - 1) != '(';
                boolean after = last + 1 < out.length() && Character.isLetterOrDigit(out.charAt(last + 1)) && out.charAt(last + 1) < 0x3131;
                boolean label = out.substring(last + 1).chars().anyMatch(Character::isLetterOrDigit);
                // "1" alone, or a part of a Latin word or a number, is what it says ("(1)", "5 V", "VC"): a box is read
                // as one letter, two or more Latin letters and digits there are a word.
                boolean word = inBox.length() > 1 && inBox.chars().allMatch(c -> c < 0x80 && Character.isLetterOrDigit(c));
                if (!misread || before || after || !label || word || inBox.equals("1") || inBox.equals(mark)) {
                    continue;
                }
                float centre = (from + to) / 2;
                out.replace(first, last + 1, mark);
                for (int i = last; i > first; i--) {
                    at.remove(i);
                }
                at.set(first, centre);
                changed = true;
            } else {
                // Left out (the reader often skips a box, ticked or not): put in before the text after it, when the box
                // stands apart (nothing read beside it).
                int next = 0;
                while (next < out.length() && at.get(next) < to) {
                    next++;
                }
                if (next >= out.length()) {
                    continue;
                }
                float centre = (from + to) / 2;
                boolean spaced = out.charAt(next) == ' ' || (next > 0 && out.charAt(next - 1) == ' ');
                if (!spaced && next > 0) {
                    continue; // read right up to the box on the left: the box may be part of what was read there
                }
                if (out.charAt(next) == ' ') {
                    out.insert(next, mark);
                    at.add(next, centre);
                } else {
                    out.insert(next, mark + " ");
                    at.add(next, centre);
                    at.add(next + 1, centre);
                }
                changed = true;
            }
        }
        if (!changed) {
            return null;
        }
        float[] places = new float[at.size()];
        for (int i = 0; i < places.length; i++) {
            places[i] = at.get(i);
        }
        return new Object[] {out.toString(), places};
    }
}
