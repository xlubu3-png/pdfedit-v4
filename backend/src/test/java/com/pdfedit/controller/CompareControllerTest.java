package com.pdfedit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import com.pdfedit.dto.CompareResult;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.PdfThumbnailService;

class CompareControllerTest {

    private final PdfDocumentStore store = new PdfDocumentStore();
    private final CompareController controller = new CompareController(store, new PdfThumbnailService());

    private String stored(String line, boolean stamp) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(line);
                cs.endText();
                if (stamp) {
                    cs.addRect(400, 100, 80, 80);
                    cs.fill();
                }
            }
            doc.save(out);
            return store.store(line + ".pdf", out.toByteArray(), 1);
        }
    }

    @Test
    void comparesTheTextAndSaysWhereThePagesAre() throws IOException {
        String a = stored("Total 100 won", false);
        String b = stored("Total 250 won", true);

        CompareResult result = controller.compare(new CompareController.CompareRequest(a, b));

        assertThat(result.fileNameA()).isEqualTo("Total 100 won.pdf");
        assertThat(result.removed()).extracting(c -> c.text()).containsExactly("100");
        assertThat(result.added()).extracting(c -> c.text()).containsExactly("250");
        assertThat(result.pagesA()).hasSize(1);
        assertThat(result.pagesA().get(0).width()).isEqualTo(PDRectangle.A4.getWidth());
    }

    @Test
    void theLookComparisonFindsTheStampAndTheChangedNumber() throws IOException {
        String a = stored("Total 100 won", false);
        String b = stored("Total 250 won", true);

        CompareController.VisualResult visual = controller.visual(a, 0, b, 0);

        assertThat(visual.comparable()).isTrue();
        assertThat(visual.areas()).anySatisfy(area -> assertThat(area.x()).isBetween(390f, 402f));
        assertThat(visual.areas()).anySatisfy(area -> assertThat(area.y() + area.height()).isBetween(700f, 715f));
    }

    @Test
    void theOriginalPageIsAPicture() throws IOException {
        byte[] png = controller.original(stored("x", false), 0, 200);

        assertThat(new String(png, 1, 3, StandardCharsets.US_ASCII)).isEqualTo("PNG");
    }

    @Test
    void bothDocumentsMustBeGiven() {
        assertThatThrownBy(() -> controller.compare(new CompareController.CompareRequest(null, "b")))
                .isInstanceOf(InvalidEditException.class);
    }
}
