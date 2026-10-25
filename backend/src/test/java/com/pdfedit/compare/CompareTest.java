package com.pdfedit.compare;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import com.pdfedit.compare.DocumentComparer.Change;
import com.pdfedit.compare.DocumentComparer.Comparison;
import com.pdfedit.text.TextLines;

class CompareTest {

    private static final PDType1Font HELVETICA = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private static List<String> chars(String s) {
        return s.chars().mapToObj(c -> String.valueOf((char) c)).toList();
    }

    @Test
    void theDiffFindsInsertionsDeletionsAndReplacements() {
        assertThat(Diff.hunks(chars("abcdef"), chars("abcdef"), 100)).isEmpty();
        assertThat(Diff.hunks(chars("abcdef"), chars("abXcdef"), 100)).containsExactly(new Diff.Hunk(2, 2, 2, 3));
        assertThat(Diff.hunks(chars("abcdef"), chars("abdef"), 100)).containsExactly(new Diff.Hunk(2, 3, 2, 2));
        assertThat(Diff.hunks(chars("abcdef"), chars("abXYef"), 100)).containsExactly(new Diff.Hunk(2, 4, 2, 4));
        assertThat(Diff.hunks(chars("xabcy"), chars("zabcw"), 100))
                .containsExactly(new Diff.Hunk(0, 1, 0, 1), new Diff.Hunk(4, 5, 4, 5));
        assertThat(Diff.hunks(chars(""), chars("ab"), 100)).containsExactly(new Diff.Hunk(0, 0, 0, 2));
    }

    @Test
    void theDiffIsMinimalOnAClassicExample() {
        // ABCABBA -> CBABAC needs 5 edits; whatever the hunks, they cover exactly 5 items.
        List<Diff.Hunk> hunks = Diff.hunks(chars("ABCABBA"), chars("CBABAC"), 100);
        int edits = hunks.stream().mapToInt(h -> (h.aTo() - h.aFrom()) + (h.bTo() - h.bFrom())).sum();
        assertThat(edits).isEqualTo(5);
    }

    @Test
    void pastTheLimitTheMiddleIsOneChange() {
        assertThat(Diff.hunks(chars("aXbXcXd"), chars("aYbYcYd"), 2)).containsExactly(new Diff.Hunk(1, 6, 1, 6));
    }

    /** A document of pages with a line of 12pt Helvetica per entry. */
    private static PDDocument doc(PDRectangle size, String[]... pages) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String[] lines : pages) {
                PDPage page = new PDPage(size);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    for (int i = 0; i < lines.length; i++) {
                        cs.beginText();
                        cs.setFont(HELVETICA, 12);
                        cs.newLineAtOffset(72, 700 - i * 20);
                        cs.showText(lines[i]);
                        cs.endText();
                    }
                }
            }
            doc.save(out);
            return Loader.loadPDF(out.toByteArray());
        }
    }

    private static PDDocument doc(String[]... pages) throws IOException {
        return doc(PDRectangle.A4, pages);
    }

    private static List<String> texts(List<Change> changes) {
        return changes.stream().map(Change::text).toList();
    }

    @Test
    void aChangedWordIsReportedAsItselfOnBothSides() throws IOException {
        try (PDDocument a = doc(new String[] {"The price is 100 won", "Unchanged line"});
                PDDocument b = doc(new String[] {"The price is 120 won", "Unchanged line"})) {
            Comparison result = DocumentComparer.compare(a, b);

            assertThat(texts(result.removed())).containsExactly("100");
            assertThat(texts(result.added())).containsExactly("120");
            assertThat(result.removedWords()).isEqualTo(1);
            // The area is around "100" on the first line, not around the whole line.
            TextLines.Area area = result.removed().get(0).area();
            assertThat(area.y()).isLessThan(700f).isGreaterThan(690f);
            assertThat(area.x()).isGreaterThan(100f);
            assertThat(area.width()).isLessThan(30f);
        }
    }

    @Test
    void anAddedLineAndARemovedLineAreFoundOnTheirPages() throws IOException {
        try (PDDocument a = doc(new String[] {"First", "Second", "Third"}, new String[] {"Page two old"});
                PDDocument b = doc(new String[] {"First", "Inserted here", "Second", "Third"}, new String[] {"Page two"})) {
            Comparison result = DocumentComparer.compare(a, b);

            assertThat(result.added()).extracting(Change::text).containsExactly("Inserted here");
            assertThat(result.added().get(0).page()).isZero();
            assertThat(result.removed()).extracting(Change::text).containsExactly("old");
            assertThat(result.removed().get(0).page()).isEqualTo(1);
        }
    }

    @Test
    void textThatOnlyMovedToTheNextPageIsNoChange() throws IOException {
        try (PDDocument a = doc(new String[] {"One", "Two", "Three"}, new String[] {"Four"});
                PDDocument b = doc(new String[] {"One", "Two"}, new String[] {"Three", "Four"})) {
            Comparison result = DocumentComparer.compare(a, b);

            assertThat(result.removed()).isEmpty();
            assertThat(result.added()).isEmpty();
        }
    }

    @Test
    void identicalDocumentsHaveNoChanges() throws IOException {
        try (PDDocument a = doc(new String[] {"Same text", "and more"}); PDDocument b = doc(new String[] {"Same text", "and more"})) {
            Comparison result = DocumentComparer.compare(a, b);

            assertThat(result.removed()).isEmpty();
            assertThat(result.added()).isEmpty();
        }
    }

    @Test
    void aBoxDrawnOnOnePageIsFoundWhereItIs() throws IOException {
        try (PDDocument a = doc(new String[] {"Same"}); PDDocument b = doc(new String[] {"Same"})) {
            try (PDPageContentStream cs = new PDPageContentStream(b, b.getPage(0), PDPageContentStream.AppendMode.APPEND, true)) {
                cs.setNonStrokingColor(0f);
                cs.addRect(300, 300, 60, 40);
                cs.fill();
            }

            List<TextLines.Area> areas = VisualDiff.areas(a, 0, b, 0);

            assertThat(areas).hasSize(1);
            TextLines.Area area = areas.get(0);
            assertThat(area.x()).isBetween(290f, 302f);
            assertThat(area.y()).isBetween(290f, 302f);
            assertThat(area.x() + area.width()).isBetween(358f, 372f);
            assertThat(area.y() + area.height()).isBetween(338f, 352f);
            assertThat(VisualDiff.areas(a, 0, a, 0)).isEmpty();
        }
    }

    @Test
    void pagesOfDifferentSizesAreNotComparedByLook() throws IOException {
        try (PDDocument a = doc(new String[] {"x"}); PDDocument b = doc(PDRectangle.LETTER, new String[] {"x"})) {
            assertThat(VisualDiff.areas(a, 0, b, 0)).isNull();
        }
    }

    @Test
    void aLongDocumentWithOneChangeIsQuick() throws IOException {
        List<String[]> pagesA = new ArrayList<>();
        List<String[]> pagesB = new ArrayList<>();
        for (int p = 0; p < 40; p++) {
            String[] lines = new String[30];
            for (int i = 0; i < lines.length; i++) {
                lines[i] = "Page " + p + " line " + i + " with some words in it";
            }
            pagesA.add(lines);
            String[] changed = lines.clone();
            if (p == 20) {
                changed[15] = "Page 20 line 15 with other words in it";
            }
            pagesB.add(changed);
        }
        try (PDDocument a = doc(pagesA.toArray(String[][]::new)); PDDocument b = doc(pagesB.toArray(String[][]::new))) {
            long start = System.nanoTime();
            Comparison result = DocumentComparer.compare(a, b);
            long millis = (System.nanoTime() - start) / 1_000_000;

            assertThat(texts(result.removed())).containsExactly("some");
            assertThat(texts(result.added())).containsExactly("other");
            assertThat(result.removed().get(0).page()).isEqualTo(20);
            assertThat(millis).isLessThan(10_000);
        }
    }
}
