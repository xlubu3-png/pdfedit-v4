package com.pdfedit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
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

import com.pdfedit.compare.DocumentComparer;
import com.pdfedit.dto.CompareResult;
import com.pdfedit.dto.ExportRequest;
import com.pdfedit.dto.PageSpec;
import com.pdfedit.dto.PersonalInfoMatch;
import com.pdfedit.dto.PersonalInfoRequest;
import com.pdfedit.dto.UploadResponse;
import com.pdfedit.ocr.OcrText;
import com.pdfedit.ocr.PageOcr;
import com.pdfedit.ocr.RecognizedLineBox;
import com.pdfedit.redact.Redaction;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.PdfExportService;
import com.pdfedit.service.PdfThumbnailService;
import com.pdfedit.service.TextEditService;
import com.pdfedit.service.TextSearchService;
import com.pdfedit.dto.FindMatch;
import com.pdfedit.text.AddedText;
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
    private final OcrController ocr = new OcrController(store, pageOcr, new RecognizedLineBox(new FontMatcher()));

    private static byte[] scannedPdf() throws Exception {
        return scannedPdf(LINES);
    }

    /** An A4 page that is nothing but a picture of printed lines, as a scanner makes it. */
    private static byte[] scannedPdf(String[] lines) throws Exception {
        File malgun = new File("C:\\Windows\\Fonts\\malgun.ttf");
        assumeThat(malgun).as("needs the Windows font Malgun Gothic").exists();
        BufferedImage scan = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scan.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setFont(Font.createFont(Font.TRUETYPE_FONT, malgun).deriveFont(36f));
        for (int i = 0; i < lines.length; i++) {
            g.drawString(lines[i], 200, 300 + 160 * i);
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
    void personalInformationOnAScanIsFoundWhereItIsAndRedactedForGood() throws Exception {
        UploadResponse upload = pdf.upload(file(scannedPdf()), null);
        ocr.recognize(upload.documentId(), 0);
        RedactionController redaction = new RedactionController(store);

        List<PersonalInfoMatch> found = redaction.findPersonalInfo(new PersonalInfoRequest(
                List.of(new PageSpec(upload.documentId(), 0, 0)), List.of("RRN", "MOBILE"), null));

        assertThat(found).extracting(PersonalInfoMatch::kind, PersonalInfoMatch::text)
                .containsExactly(tuple("RRN", "900101-1234567"), tuple("MOBILE", "010-2345-6789"));
        // Where the number was drawn: after the label, to the end of the line.
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        g.setFont(Font.createFont(Font.TRUETYPE_FONT, new File("C:\\Windows\\Fonts\\malgun.ttf")).deriveFont(36f));
        float k = PDRectangle.A4.getWidth() / WIDTH;
        float numberStart = (200 + g.getFontMetrics().stringWidth("주민등록번호 ")) * k;
        float numberEnd = (200 + g.getFontMetrics().stringWidth(LINES[1])) * k;
        g.dispose();
        PersonalInfoMatch rrn = found.get(0);
        assertThat(rrn.x()).as("starts at the number, not the label").isBetween(numberStart - 12, numberStart + 2);
        assertThat(rrn.x() + rrn.width()).isGreaterThanOrEqualTo(numberEnd - 1);

        store.setRedactions(upload.documentId(), 0,
                List.of(new Redaction("r", rrn.x(), rrn.y(), rrn.width(), rrn.height())));
        byte[] exported = pdf.export(new ExportRequest(List.of(new PageSpec(upload.documentId(), 0, 0)), "a.pdf",
                false, null, null)).getBody();

        // Read the exported page as a scan again: the number is gone from the picture itself. (The other lines are
        // in the export's text layer, so they count as real text and are not read again.)
        String seen = String.join(" ", pageOcr.recognize(exported, 0).lines().stream().map(OcrText::text).toList());
        assertThat(seen).contains("주민등록번호").doesNotContain("1234567");
        try (PDDocument doc = Loader.loadPDF(exported)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("010-2345-6789").doesNotContain("1234567");
        }
    }

    @Test
    void recognizedTextIsFoundBySearchButNotReplaced() throws Exception {
        UploadResponse upload = pdf.upload(file(scannedPdf()), null);
        ocr.recognize(upload.documentId(), 0);
        TextSearchService search = new TextSearchService(store, textEditService);
        List<PageSpec> pages = List.of(new PageSpec(upload.documentId(), 0, 0));

        assertThat(search.find(pages, "6789", false))
                .containsExactly(new FindMatch(upload.documentId(), 0, -3, "연락처 010-2345-6789", true));
        assertThat(search.replace(pages, "6789", "9999", false).replacements()).isZero();
    }

    @Test
    void twoScansAreComparedByTheirRecognizedText() throws Exception {
        UploadResponse before = pdf.upload(file(scannedPdf()), null);
        String[] changed = LINES.clone();
        changed[2] = "연락처 010-9876-6789";
        UploadResponse after = pdf.upload(file(scannedPdf(changed)), null);
        ocr.recognize(before.documentId(), 0);
        ocr.recognize(after.documentId(), 0);

        CompareResult result = new CompareController(store, new PdfThumbnailService())
                .compare(new CompareController.CompareRequest(before.documentId(), after.documentId()));

        assertThat(result.removed()).extracting(DocumentComparer.Change::text).containsExactly("010-2345-6789");
        assertThat(result.added()).extracting(DocumentComparer.Change::text).containsExactly("010-9876-6789");
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
        OcrController savedOcr = new OcrController(saved, pageOcr, new RecognizedLineBox(new FontMatcher()));
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
    void aRecognizedLineBecomesATextBoxThatHidesTheScannedLettersAndCanBeRewritten() throws Exception {
        String[] lines = {"공문서 스캔 시험 첫째 줄입니다", "연락처 010-2345-6789"};
        UploadResponse upload = pdf.upload(file(scannedPdf(lines)), null);
        OcrText phone = ocr.recognize(upload.documentId(), 0).lines().get(1);

        AddedText box = ocr.textBox(upload.documentId(), 0, 1);

        assertThat(plain(box.text())).isEqualTo(plain(lines[1]));
        Color ink = Color.decode(box.color());
        assertThat(Math.max(ink.getRed(), Math.max(ink.getGreen(), ink.getBlue()))).as("the black of the scan")
                .isLessThan(0x40);
        assertThat(box.cover().color()).as("the white paper around it").isEqualTo("#ffffff");
        assertThat(box.cover().contains(phone.x(), phone.y())).isTrue();
        assertThat(box.cover().contains(phone.x() + phone.width(), phone.y() + phone.height())).isTrue();
        // Drawn 36 px high at 200 dpi, its baseline 460 px from the top.
        float k = PDRectangle.A4.getHeight() / HEIGHT;
        assertThat(box.fontSize()).isBetween(36 * k * 0.8f, 36 * k * 1.15f);
        assertThat(box.y()).isCloseTo(PDRectangle.A4.getHeight() - 460 * k, within(2f));

        // The number is rewritten in the box: the export holds the new one, and nothing of the old.
        AddedText rewritten = new AddedText("b1", "연락처 010-9999-0000", box.x(), box.y(), null, box.fontSize(),
                false, box.color(), List.of(), box.cover());
        store.setAddedTexts(upload.documentId(), 0,
                textEditService.checkAdded(store.getContent(upload.documentId()), 0, List.of(rewritten)));

        String text = exportedText(upload.documentId(), false);
        assertThat(text).contains(plain(lines[0]), "010-9999-0000").doesNotContain("2345");
        assertThat(new TextSearchService(store, textEditService).find(List.of(new PageSpec(upload.documentId(), 0, 0)),
                "2345", false)).as("the rewritten line is not found by what was recognized").isEmpty();
        byte[] picture = pdf.export(new ExportRequest(List.of(new PageSpec(upload.documentId(), 0, 0)), "a.pdf", true,
                null, null)).getBody();
        String seen = String.join(" ", pageOcr.recognize(picture, 0).lines().stream().map(OcrText::text).toList());
        assertThat(seen).as("the scanned number is covered on the page itself").contains("9999-0000")
                .doesNotContain("2345");
    }

    @Test
    void aLineThatWasNotRecognizedCannotBeRewritten() throws Exception {
        UploadResponse upload = pdf.upload(file(scannedPdf()), null);
        ocr.recognize(upload.documentId(), 0);

        assertThatThrownBy(() -> ocr.textBox(upload.documentId(), 0, 9)).isInstanceOf(InvalidEditException.class);
        assertThatThrownBy(() -> ocr.textBox(upload.documentId(), 0, -1)).isInstanceOf(InvalidEditException.class);
    }

    @Test
    void aPageThatIsNotThereIsRefused() throws Exception {
        UploadResponse blank = pdf.createBlankDocument(200, 200);

        assertThatThrownBy(() -> ocr.recognize(blank.documentId(), 1)).isInstanceOf(InvalidEditException.class);
        assertThat(ocr.recognize(blank.documentId(), 0).lines()).as("a blank page says nothing").isEmpty();
    }
}
