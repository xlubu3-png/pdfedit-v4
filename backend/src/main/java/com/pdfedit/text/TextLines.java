package com.pdfedit.text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * The text of a page line by line, in reading order, with where every character of it is: what it takes to
 * point at a piece of text on the page (to redact it, or to mark it as changed).
 */
public final class TextLines {

    private TextLines() {
    }

    /** A rectangle in PDF user space (origin bottom-left, y-up), in points. */
    public record Area(float x, float y, float width, float height) {
    }

    /** A word (a run of characters between spaces) and the area it covers. */
    public record Word(String text, Area area) {
    }

    /** One line of text; a space stands for a gap between words that has no character of its own. */
    public static final class Line {
        private final String text;
        /** One per character of {@code text}: where it is; null for a gap between words. */
        private final List<Area> boxes;

        private Line(String text, List<Area> boxes) {
            this.text = text;
            this.boxes = boxes;
        }

        /**
         * A line whose characters are where {@code boxes} say (one per character of the text, null for a space):
         * text recognized on a scan, for instance.
         */
        public static Line of(String text, List<Area> boxes) {
            if (boxes.size() != text.length()) {
                throw new IllegalArgumentException("One box per character is needed");
            }
            return new Line(text, new ArrayList<>(boxes));
        }

        /** Where a character of the page's text is: from below the baseline to above the capitals. */
        private static Area box(TextPosition tp, PDRectangle crop) {
            float size = tp.getYScale() > 0.01f ? tp.getYScale() : Math.max(tp.getFontSizeInPt(), 6f);
            float left = tp.getXDirAdj() + crop.getLowerLeftX();
            float baseline = crop.getHeight() - tp.getYDirAdj() + crop.getLowerLeftY();
            return new Area(left, baseline - size * 0.25f, tp.getWidthDirAdj(), size * 1.1f);
        }

        public String text() {
            return text;
        }

        /**
         * The area of characters start (inclusive) to end (exclusive), a little larger than the letters. Null when
         * there is no character in the range.
         */
        public Area area(int start, int end) {
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (int i = start; i < end; i++) {
                Area box = boxes.get(i);
                if (box == null) {
                    continue;
                }
                minX = Math.min(minX, box.x());
                maxX = Math.max(maxX, box.x() + box.width());
                minY = Math.min(minY, box.y());
                maxY = Math.max(maxY, box.y() + box.height());
            }
            if (minX == Float.MAX_VALUE) {
                return null;
            }
            float pad = 0.5f;
            return new Area(minX - pad, minY - pad, maxX - minX + 2 * pad, maxY - minY + 2 * pad);
        }

        /** The words of the line, left to right. */
        public List<Word> words() {
            List<Word> words = new ArrayList<>();
            int start = -1;
            for (int i = 0; i <= text.length(); i++) {
                boolean blank = i == text.length() || Character.isWhitespace(text.charAt(i));
                if (!blank && start < 0) {
                    start = i;
                } else if (blank && start >= 0) {
                    Area area = area(start, i);
                    if (area != null) {
                        words.add(new Word(text.substring(start, i), area));
                    }
                    start = -1;
                }
            }
            return words;
        }
    }

    public static List<Line> of(PDDocument doc, int pageIndex) throws IOException {
        PDPage page = doc.getPage(pageIndex);
        PDRectangle crop = page.getCropBox();
        List<Line> lines = new ArrayList<>();
        // The positions themselves are the text, one entry per character (null for a gap between words), so
        // an index into a line's string is an index into its positions.
        StringBuilder text = new StringBuilder();
        List<Area> positions = new ArrayList<>();
        Runnable endLine = () -> {
            if (!positions.isEmpty()) {
                lines.add(new Line(text.toString(), new ArrayList<>(positions)));
            }
            text.setLength(0);
            positions.clear();
        };
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String ignored, List<TextPosition> textPositions) {
                for (TextPosition tp : textPositions) {
                    String unicode = tp.getUnicode();
                    if (unicode == null || unicode.isEmpty()) {
                        continue;
                    }
                    Area box = Line.box(tp, crop);
                    for (int i = 0; i < unicode.length(); i++) {
                        text.append(unicode.charAt(i));
                        positions.add(box);
                    }
                }
            }

            @Override
            protected void writeWordSeparator() {
                if (!positions.isEmpty()) {
                    text.append(' ');
                    positions.add(null);
                }
            }

            @Override
            protected void writeLineSeparator() {
                endLine.run();
            }
        };
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.getText(doc);
        endLine.run();
        return lines;
    }
}
