package com.pdfedit.text;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What the user did to one text run of a page: the new text, optionally a different font, size,
 * weight or colour, and optionally a move. A null override means "as the original".
 *
 * @param fontFamily family name as listed by {@code GET /fonts}, or null for the run's own font
 * @param fontSize   size in points, or null for the original size
 * @param bold       true/false to force the weight, or null for the original
 * @param color      "#rrggbb", or null for the colour the run was printed in
 * @param dx         how far the run moved right, in points (PDF user space)
 * @param dy         how far the run moved up, in points; negative is down
 * @param spans      parts of the new text with a look of their own (bold, colour), on top of the line's look;
 *                   never null, sorted, not overlapping
 */
public record RunEdit(String text, String fontFamily, Float fontSize, Boolean bold, String color,
                      float dx, float dy, List<Span> spans) {

    /**
     * Characters {@code start} (included) to {@code end} (excluded) of the new text, drawn bold or not, in a
     * colour, at a size (points) and underlined or not of their own; a null field leaves that to the line.
     */
    public record Span(int start, int end, Boolean bold, String color, Float size, Boolean underline) {

        public Span(int start, int end, Boolean bold, String color) {
            this(start, end, bold, color, null, null);
        }

        /** True when the span changes something about its letters. */
        boolean changesLook() {
            return bold != null || color != null || size != null || underline != null;
        }
    }

    /** A stretch of the new text with one look: the line's, or a span's. */
    public record Segment(int start, int end, Boolean bold, String color, Float size, Boolean underline) {

        public Segment(int start, int end, Boolean bold, String color) {
            this(start, end, bold, color, null, null);
        }
    }

    public RunEdit {
        spans = spans == null ? List.of() : List.copyOf(spans);
    }

    public RunEdit(String text, String fontFamily, Float fontSize, Boolean bold, String color, float dx, float dy) {
        this(text, fontFamily, fontSize, bold, color, dx, dy, List.of());
    }

    public static RunEdit ofText(String text) {
        return new RunEdit(text, null, null, null, null, 0f, 0f);
    }

    /** True when anything about the look differs from the original: the font, size, weight or colour, or parts of the line. */
    public boolean restyled() {
        return lineRestyled() || !spans.isEmpty();
    }

    /**
     * True when the whole line has another font, size, weight or colour: a glyph-for-glyph copy of the original
     * can't honour that. Parts of the line with a look of their own (spans) can be drawn with the page's glyphs.
     */
    public boolean lineRestyled() {
        return fontFamily != null || fontSize != null || bold != null || color != null;
    }

    public boolean moved() {
        return dx != 0f || dy != 0f;
    }

    /**
     * The same edit with other text. The spans keep to the letters they were on: what is typed inside a span
     * joins it, what is typed beside it does not, and a span whose letters were all replaced covers the
     * replacement.
     */
    public RunEdit withText(String newText) {
        return new RunEdit(newText, fontFamily, fontSize, bold, color, dx, dy, remap(spans, text, newText));
    }

    /** Where the spans of {@code oldText} lie once it has become {@code newText}. */
    public static List<Span> remap(List<Span> spans, String oldText, String newText) {
        if (spans.isEmpty() || oldText.equals(newText)) {
            return spans;
        }
        int prefix = 0;
        int limit = Math.min(oldText.length(), newText.length());
        while (prefix < limit && oldText.charAt(prefix) == newText.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < limit - prefix
                && oldText.charAt(oldText.length() - 1 - suffix) == newText.charAt(newText.length() - 1 - suffix)) {
            suffix++;
        }
        int oldEnd = oldText.length() - suffix; // the changed stretch is [prefix, oldEnd) before, [prefix, newEnd) after
        int newEnd = newText.length() - suffix;
        int delta = newEnd - oldEnd;
        List<Span> out = new ArrayList<>();
        for (Span span : spans) {
            int start;
            int end;
            if (span.end() <= prefix) {
                start = span.start();
                end = span.end();
            } else if (span.start() >= oldEnd) {
                start = span.start() + delta;
                end = span.end() + delta;
            } else {
                start = Math.min(span.start(), prefix);
                end = span.end() > oldEnd ? span.end() + delta : newEnd;
            }
            if (end > start) {
                out.add(new Span(start, end, span.bold(), span.color(), span.size(), span.underline()));
            }
        }
        return out;
    }

    /**
     * Spans fit for drawing {@code textLength} characters: clipped to the text, empty ones and ones that change
     * nothing dropped, in order, and where two overlap the later one wins.
     */
    public static List<Span> cleanSpans(List<Span> spans, int textLength) {
        if (spans == null || spans.isEmpty()) {
            return List.of();
        }
        List<Span> sorted = spans.stream()
                .filter(s -> s != null && s.changesLook())
                .map(s -> new Span(Math.max(0, s.start()), Math.min(textLength, s.end()), s.bold(), s.color(),
                        s.size(), s.underline()))
                .filter(s -> s.end() > s.start())
                .sorted(Comparator.comparingInt(Span::start).thenComparingInt(Span::end))
                .toList();
        List<Span> out = new ArrayList<>();
        for (Span span : sorted) {
            if (!out.isEmpty() && out.get(out.size() - 1).end() > span.start()) {
                Span previous = out.remove(out.size() - 1);
                if (span.start() > previous.start()) {
                    out.add(new Span(previous.start(), span.start(), previous.bold(), previous.color(), previous.size(),
                        previous.underline()));
                }
            }
            out.add(span);
        }
        return out;
    }

    /** The text cut where the look changes: every character in exactly one segment, in order. */
    public List<Segment> segments() {
        int length = text == null ? 0 : text.length();
        List<Segment> out = new ArrayList<>();
        int at = 0;
        for (Span span : cleanSpans(spans, length)) {
            if (span.start() > at) {
                out.add(new Segment(at, span.start(), null, null));
            }
            out.add(new Segment(span.start(), span.end(), span.bold(), span.color(), span.size(), span.underline()));
            at = span.end();
        }
        if (at < length) {
            out.add(new Segment(at, length, null, null));
        }
        return out;
    }

    /** "#rrggbb" to a colour; {@code fallback} when the text is missing or not in that form. */
    public static Color colour(String hex, Color fallback) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}")) {
            return fallback;
        }
        return new Color(Integer.parseInt(hex.substring(1), 16));
    }

    public static String hex(Color colour) {
        return String.format("#%06x", colour.getRGB() & 0xFFFFFF);
    }
}
