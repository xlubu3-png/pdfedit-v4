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
        /** One per character of {@code text}; null for a gap between words. */
        private final List<TextPosition> positions;
        private final PDRectangle crop;

        Line(String text, List<TextPosition> positions, PDRectangle crop) {
            this.text = text;
            this.positions = positions;
            this.crop = crop;
        }

        public String text() {
            return text;
        }

        /**
         * The area of characters start (inclusive) to end (exclusive), a little larger than the letters: from
         * below the baseline to above the capitals. Null when there is no character in the range.
         */
        public Area area(int start, int end) {
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (int i = start; i < end; i++) {
                TextPosition tp = positions.get(i);
                if (tp == null) {
                    continue;
                }
                float size = tp.getYScale() > 0.01f ? tp.getYScale() : Math.max(tp.getFontSizeInPt(), 6f);
                float left = tp.getXDirAdj() + crop.getLowerLeftX();
                float baseline = crop.getHeight() - tp.getYDirAdj() + crop.getLowerLeftY();
                minX = Math.min(minX, left);
                maxX = Math.max(maxX, left + tp.getWidthDirAdj());
                minY = Math.min(minY, baseline - size * 0.25f);
                maxY = Math.max(maxY, baseline + size * 0.85f);
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
        List<TextPosition> positions = new ArrayList<>();
        Runnable endLine = () -> {
            if (!positions.isEmpty()) {
                lines.add(new Line(text.toString(), new ArrayList<>(positions), crop));
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
                    for (int i = 0; i < unicode.length(); i++) {
                        text.append(unicode.charAt(i));
                        positions.add(tp);
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
