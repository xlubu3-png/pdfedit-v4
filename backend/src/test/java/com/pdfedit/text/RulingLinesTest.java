package com.pdfedit.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

/** A table's borders are found however the program drew them, and measured from the page's top-left corner. */
class RulingLinesTest {

    private interface Drawing {
        void draw(PDPageContentStream cs) throws IOException;
    }

    private static RulingLines.Lines linesOf(Drawing drawing) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(200, 300));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                drawing.draw(cs);
            }
            return RulingLines.of(page);
        }
    }

    @Test
    void strokedLinesAreFoundAndMeasuredFromTheTop() throws IOException {
        RulingLines.Lines lines = linesOf(cs -> {
            cs.moveTo(10, 250);
            cs.lineTo(110, 250);
            cs.moveTo(10, 200);
            cs.lineTo(10, 250);
            cs.stroke();
        });

        assertThat(lines.horizontal()).singleElement().satisfies(h -> {
            assertThat(h.y()).isCloseTo(50, within(0.01));
            assertThat(h.x1()).isCloseTo(10, within(0.01));
            assertThat(h.x2()).isCloseTo(110, within(0.01));
        });
        assertThat(lines.vertical()).singleElement().satisfies(v -> {
            assertThat(v.x()).isCloseTo(10, within(0.01));
            assertThat(v.y1()).isCloseTo(50, within(0.01));
            assertThat(v.y2()).isCloseTo(100, within(0.01));
        });
    }

    @Test
    void thinFilledBarsAsExcelDrawsBordersAreLinesButShadedBoxesAreNot() throws IOException {
        RulingLines.Lines lines = linesOf(cs -> {
            cs.addRect(0, 0, 200, 300); // the white page
            cs.addRect(20, 100, 80, 40); // a shaded cell
            cs.addRect(20, 140, 80, 0.75f); // a border under a row
            cs.addRect(20, 100, 0.75f, 40); // a border left of a column
            cs.fill();
        });

        assertThat(lines.horizontal()).singleElement().satisfies(h -> assertThat(h.y()).isCloseTo(300 - 140.375, within(0.01)));
        assertThat(lines.vertical()).singleElement().satisfies(v -> assertThat(v.x()).isCloseTo(20.375, within(0.01)));
    }

    @Test
    void anOutlinedBoxGivesItsFourSidesAndPiecesOfALineAreJoined() throws IOException {
        RulingLines.Lines lines = linesOf(cs -> {
            cs.addRect(20, 100, 80, 40);
            cs.stroke();
            // one row border drawn in two pieces, cell by cell
            cs.moveTo(20, 120);
            cs.lineTo(60, 120);
            cs.moveTo(60, 120);
            cs.lineTo(100, 120);
            cs.stroke();
        });

        assertThat(lines.horizontal()).hasSize(3);
        assertThat(lines.horizontal()).anySatisfy(h -> {
            assertThat(h.y()).isCloseTo(180, within(0.01));
            assertThat(h.x1()).isCloseTo(20, within(0.01));
            assertThat(h.x2()).isCloseTo(100, within(0.01));
        });
        assertThat(lines.vertical()).hasSize(2);
    }

    @Test
    void slantedLinesCurvesAndTinyTicksAreNotBorders() throws IOException {
        RulingLines.Lines lines = linesOf(cs -> {
            cs.moveTo(10, 10);
            cs.lineTo(100, 60);
            cs.moveTo(10, 100);
            cs.curveTo(30, 120, 60, 120, 90, 100);
            cs.moveTo(10, 200);
            cs.lineTo(12, 200);
            cs.stroke();
        });

        assertThat(lines.horizontal()).isEmpty();
        assertThat(lines.vertical()).isEmpty();
    }
}
