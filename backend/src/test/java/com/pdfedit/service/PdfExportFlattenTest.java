package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import com.pdfedit.dto.PageSpec;
import com.pdfedit.dto.TextEditItem;
import com.pdfedit.text.FontMatcher;

/** Exporting "as a picture" must leave nothing of the original text - the point of the option. */
class PdfExportFlattenTest {

    private final PdfDocumentStore store = new PdfDocumentStore();
    private final TextEditService editService = new TextEditService(new FontMatcher());
    private final PdfExportService export = new PdfExportService(store, editService);

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String editedDocument() throws IOException {
        byte[] pdf = TestPdfs.koreanPage();
        String id = store.store("a.pdf", pdf, 1);
        int hello = editService.describePage(pdf, 0, Map.of()).runs().stream()
                .filter(r -> r.text().startsWith("Hello")).findFirst().orElseThrow().index();
        editService.recordEdits(pdf, 0, store.pageEdits(id, 0), List.of(new TextEditItem(hello, "Hold World")));
        return id;
    }

    @Test
    void anOrdinaryExportStillHoldsTheTextThatWasCoveredUp() throws IOException {
        String id = editedDocument();

        String text = textOf(export.export(List.of(new PageSpec(id, 0, 0))));

        // the original is still in the file, unseen under the cover-up: that is what flattening removes
        assertThat(text).contains("Hello");
    }

    @Test
    void aFlattenedExportHasNoTextAtAllButLooksTheSame() throws IOException {
        String id = editedDocument();
        byte[] normal = export.export(List.of(new PageSpec(id, 0, 0)));

        byte[] flat = export.export(List.of(new PageSpec(id, 0, 0)), true);

        assertThat(textOf(flat).strip()).isEmpty();
        try (PDDocument a = Loader.loadPDF(normal); PDDocument b = Loader.loadPDF(flat)) {
            assertThat(b.getNumberOfPages()).isEqualTo(a.getNumberOfPages());
            assertThat(b.getPage(0).getMediaBox().getWidth()).isEqualTo(a.getPage(0).getMediaBox().getWidth(),
                    org.assertj.core.data.Offset.offset(0.5f));
            BufferedImage before = TestPdfs.render(normal);
            BufferedImage after = TestPdfs.render(flat);
            assertThat(after.getWidth()).isEqualTo(before.getWidth());
            // JPEG compression blurs edges a little, but the page must plainly be the same page
            assertThat(TestPdfs.visiblyDifferentPixels(before, after)).isLessThan(before.getWidth() * before.getHeight() / 200L);
        }
    }

    @Test
    void flatteningKeepsEveryPageInOrder() throws IOException {
        String id = store.store("a.pdf", TestPdfs.koreanPage(), 1);

        byte[] flat = export.export(List.of(new PageSpec(id, 0, 0), new PageSpec(id, 0, 90)), true);

        try (PDDocument doc = Loader.loadPDF(flat)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
            assertThat(doc.getPage(1).getMediaBox().getWidth()).isGreaterThan(doc.getPage(0).getMediaBox().getWidth());
        }
    }
}
