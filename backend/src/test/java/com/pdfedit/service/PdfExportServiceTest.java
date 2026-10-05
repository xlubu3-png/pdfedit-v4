package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import com.pdfedit.dto.PageSpec;
import com.pdfedit.dto.TextEditItem;
import com.pdfedit.text.FontMatcher;
import com.pdfedit.text.RunEdit;

class PdfExportServiceTest {

    private final PdfDocumentStore store = new PdfDocumentStore();
    private final TextEditService textEditService = new TextEditService(new FontMatcher());
    private final PdfExportService exportService = new PdfExportService(store, textEditService);

    @Test
    void exportMergesPagesFromMultipleDocumentsInRequestedOrderAndAppliesRotation() throws IOException {
        String docA = store.store("a.pdf", singlePagePdf("A"), 1);
        String docB = store.store("b.pdf", singlePagePdf("B"), 1);

        byte[] result = exportService.export(List.of(
                new PageSpec(docB, 0, 90),
                new PageSpec(docA, 0, 0)));

        try (PDDocument output = Loader.loadPDF(result)) {
            assertThat(output.getNumberOfPages()).isEqualTo(2);
            assertThat(output.getPage(0).getRotation()).isEqualTo(90);
            assertThat(output.getPage(1).getRotation()).isEqualTo(0);
        }
    }

    @Test
    void exportBakesTextEditsRecordedForTheSourcePage() throws IOException {
        byte[] pdf = TestPdfs.koreanPage();
        String docId = store.store("korean.pdf", pdf, 1);
        int hello = textEditService.describePage(pdf, 0, Map.of()).runs().stream()
                .filter(r -> r.text().startsWith("Hello")).findFirst().orElseThrow().index();
        textEditService.recordEdits(pdf, 0, store.pageEdits(docId, 0), List.of(new TextEditItem(hello, "Hold")));

        byte[] result = exportService.export(List.of(new PageSpec(docId, 0, 0)));

        assertThat(TestPdfs.differingPixels(TestPdfs.render(result),
                TestPdfs.render(TestPdfs.koreanPage("Hold")))).isLessThanOrEqualTo(TestPdfs.SUBPIXEL_NOISE);
    }

    @Test
    void editsOfOneDocumentDoNotLeakIntoAnotherDocumentWithTheSameContent() throws IOException {
        byte[] pdf = TestPdfs.koreanPage();
        String edited = store.store("edited.pdf", pdf, 1);
        String untouched = store.store("untouched.pdf", pdf, 1);
        RunEdit replacement = RunEdit.ofText("Hold");
        store.pageEdits(edited, 0).put(0, replacement);

        byte[] result = exportService.export(List.of(new PageSpec(untouched, 0, 0)));

        assertThat(TestPdfs.differingPixels(TestPdfs.render(result), TestPdfs.render(pdf))).isZero();
    }

    private static byte[] singlePagePdf(String label) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(label);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
