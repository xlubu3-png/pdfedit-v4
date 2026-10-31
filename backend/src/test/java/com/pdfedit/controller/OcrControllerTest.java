package com.pdfedit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.pdfedit.dto.ExportRequest;
import com.pdfedit.dto.PageSpec;
import com.pdfedit.dto.UploadResponse;
import com.pdfedit.ocr.OcrText;
import com.pdfedit.ocr.PageOcr;
import com.pdfedit.redact.Redaction;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.PdfExportService;
import com.pdfedit.service.PdfThumbnailService;
import com.pdfedit.service.TextEditService;
import com.pdfedit.text.FontMatcher;

/** A scanned page: recognized, kept with the document, and exported as a page that can be searched. */
class OcrControllerTest {

    private static final String[] LINES = {"공문서 스캔 시험 첫째 줄입니다", "주민등록번호 900101-1234567", "연락처 010-2345-6789"};
    /** The picture is the page at 200 dpi; line i has its baseline at y = 300 + 160 i pixels from the top. */
    private static final int WIDTH = 1654;
    private static final int HEIGHT = 2339;

    private final PdfDocumentStore store = new PdfDocumentStore();
    private final TextEditService textEditService = new TextEditService(new FontMatcher());
    private final PdfController pdf = new PdfController(
            store, new PdfThumbnailService(), new PdfExportService(store, textEditService), textEditService);
    private final PageOcr pageOcr = new PageOcr(textEditService);
    private final OcrController ocr = new OcrController(store, pageOcr);

    /** An A4 page that is nothing but a picture of three printed lines, as a scanner makes it. */
    private static byte[] scannedPdf() throws Exception {
        File malgun = new File("C:\\Windows\\Fonts\\malgun.ttf");
        assumeThat(malgun).as("needs the Windows font Malgun Gothic").exists();
        BufferedImage scan = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scan.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setFont(Font.createFont(Font.TRUETYPE_FONT, malgun).deriveFont(36f));
        for (int i = 0; i < LINES.length; i++) {
            g.drawString(LINES[i], 200, 300 + 160 * i);
        }
        g.dispose();
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(LosslessFactory.createFromImage(doc, scan), 0, 0, PDRectangle.A4.getWidth(),
                        PDRectangle.A4.getHeight());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "scan.pdf", "application/pdf", bytes);
    }

    private String exportedText(String documentId, boolean flatten) throws IOException {
        byte[] exported = pdf.export(new ExportRequest(List.of(new PageSpec(documentId, 0, 0)), "a.pdf", flatten,
                null, null)).getBody();
        try (PDDocument doc = Loader.loadPDF(exported)) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", "");
        }
    }

    private static String plain(String s) {
        return s.replaceAll("\\s+", "");
    }

    @Test
    void aScanIsRecognizedPlacedOnThePageAndExportedAsSearchableText() throws Exception {
        UploadResponse upload = pdf.upload(file(scannedPdf()), null);
        assertThat(upload.scanned()).as("a page that is only a picture looks scanned").isTrue();
        assertThat(exportedText(upload.documentId(), false)).isEmpty();

        OcrController.OcrResponse result = ocr.recognize(upload.documentId(), 0);

        assertThat(result.lines()).extracting(l -> plain(l.text()))
                .containsExactly(plain(LINES[0]), plain(LINES[1]), plain(LINES[2]));
        float pointsPerPixel = PDRectangle.A4.getHeight() / HEIGHT;
        OcrText second = result.lines().get(1);
        float baseline = PDRectangle.A4.getHeight() - 460 * pointsPerPixel;
        assertThat(baseline).isBetween(second.y(), second.y() + second.height());
        assertThat(second.x()).isBetween(200 * pointsPerPixel - 10, 200 * pointsPerPixel + 5);
        assertThat(ocr.recognized(upload.documentId(), 0).lines()).isEqualTo(result.lines());

        String text = exportedText(upload.documentId(), false);
        assertThat(text).contains(plain(LINES[0]), "900101-1234567", "010-2345-6789");
        assertThat(exportedText(upload.documentId(), true)).as("a picture-only export keeps no text").isEmpty();
    }

    @Test
    void aRecognizedLineInsideARedactionAreaIsNotExported() throws Exception {
        UploadResponse upload = pdf.upload(file(scannedPdf()), null);
        OcrText number = ocr.recognize(upload.documentId(), 0).lines().get(1);
        store.setRedactions(upload.documentId(), 0, List.of(new Redaction("r", number.x() + number.width() / 2,
                number.y(), number.width() / 2, number.height())));

        String text = exportedText(upload.documentId(), false);

        assertThat(text).contains("010-2345-6789").doesNotContain("1234567");
    }

    @Test
    void realTextIsNotRecognizedASecondTime() throws Exception {
        byte[] typed;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                cs.newLineAtOffset(72, 700);
                cs.showText("Real text 2026");
                cs.endText();
            }
            doc.save(out);
            typed = out.toByteArray();
        }
        UploadResponse upload = pdf.upload(file(typed), null);
        assertThat(upload.scanned()).isFalse();

        OcrController.OcrResponse result = ocr.recognize(upload.documentId(), 0);

        assertThat(result.lines()).isEmpty();
        assertThat(result.alreadyText()).isEqualTo(1);
        assertThat(exportedText(upload.documentId(), false)).isEqualTo("Realtext2026");
    }

    @Test
    void whatWasRecognizedIsKeptAcrossARestartAndGoesWithADuplicate(@TempDir Path data) throws Exception {
        PdfDocumentStore saved = new PdfDocumentStore(data, Duration.ofDays(30));
        PdfController savedPdf = new PdfController(saved, new PdfThumbnailService(),
                new PdfExportService(saved, textEditService), textEditService);
        OcrController savedOcr = new OcrController(saved, pageOcr);
        UploadResponse upload = savedPdf.upload(file(scannedPdf()), null);
        List<OcrText> lines = savedOcr.recognize(upload.documentId(), 0).lines();

        PdfDocumentStore reopened = new PdfDocumentStore(data, Duration.ofDays(30));
        assertThat(reopened.ocr(upload.documentId(), 0)).isEqualTo(lines);

        UploadResponse copy = savedPdf.duplicatePage(upload.documentId(), 0);
        assertThat(saved.ocr(copy.documentId(), 0)).isEqualTo(lines);

        savedOcr.forget(upload.documentId(), 0);
        assertThat(saved.ocr(upload.documentId(), 0)).isEmpty();
        assertThat(saved.ocr(copy.documentId(), 0)).isEqualTo(lines);
    }

    @Test
    void aPageThatIsNotThereIsRefused() throws Exception {
        UploadResponse blank = pdf.createBlankDocument(200, 200);

        assertThatThrownBy(() -> ocr.recognize(blank.documentId(), 1)).isInstanceOf(InvalidEditException.class);
        assertThat(ocr.recognize(blank.documentId(), 0).lines()).as("a blank page says nothing").isEmpty();
    }
}
