package com.pdfedit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pdfedit.dto.ExportRequest;
import com.pdfedit.dto.PageSpec;
import com.pdfedit.dto.PersonalInfoMatch;
import com.pdfedit.dto.PersonalInfoRequest;
import com.pdfedit.redact.Redaction;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.PdfExportService;
import com.pdfedit.service.PdfThumbnailService;
import com.pdfedit.service.TextEditService;
import com.pdfedit.text.FontMatcher;

class RedactionControllerTest {

    private final PdfDocumentStore store = new PdfDocumentStore();
    private final TextEditService textEditService = new TextEditService(new FontMatcher());
    private final PdfController pdfController = new PdfController(
            store, new PdfThumbnailService(), new PdfExportService(store, textEditService), textEditService);
    private final RedactionController controller = new RedactionController(store);

    private static byte[] pdfWith(String text) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void foundPersonalInfoIsRedactedOnExportAndTheReportSaysSo() throws IOException {
        String id = store.store("a.pdf", pdfWith("Call 010-1234-5678 today"), 1);

        List<PersonalInfoMatch> found = controller.findPersonalInfo(
                new PersonalInfoRequest(List.of(new PageSpec(id, 0, 0)), List.of("MOBILE", "EMAIL"), null));
        assertThat(found).extracting(PersonalInfoMatch::text).containsExactly("010-1234-5678");
        assertThat(found.get(0).label()).isEqualTo("휴대전화번호");

        PersonalInfoMatch m = found.get(0);
        controller.saveRedactions(id, 0, List.of(new Redaction("r1", m.x(), m.y(), m.width(), m.height())));
        assertThat(controller.redactions(id, 0)).hasSize(1);

        var response = pdfController.export(new ExportRequest(List.of(new PageSpec(id, 0, 0)), "out.pdf"));

        assertThat(response.getHeaders().getFirst(PdfController.REDACTION_HEADER))
                .isEqualTo("areas=1;removed=13;pictures=");
        try (PDDocument exported = Loader.loadPDF(response.getBody())) {
            String text = new PDFTextStripper().getText(exported);
            assertThat(text).contains("Call").contains("today").doesNotContain("1234");
        }
        // The stored document itself is untouched: the areas can still be changed or removed.
        try (PDDocument stored = Loader.loadPDF(store.getContent(id))) {
            assertThat(new PDFTextStripper().getText(stored)).contains("010-1234-5678");
        }
    }

    @Test
    void anExportWithoutAreasReportsNone() throws IOException {
        String id = store.store("a.pdf", pdfWith("Nothing to hide"), 1);

        var response = pdfController.export(new ExportRequest(List.of(new PageSpec(id, 0, 0)), "out.pdf"));

        assertThat(response.getHeaders().getFirst(PdfController.REDACTION_HEADER)).isEqualTo("areas=0;removed=0;pictures=");
    }

    @Test
    void aTermAndNoKindsLooksForTheTermOnly() throws IOException {
        String id = store.store("a.pdf", pdfWith("Project Falcon budget 010-1234-5678"), 1);

        List<PersonalInfoMatch> found = controller.findPersonalInfo(
                new PersonalInfoRequest(List.of(new PageSpec(id, 0, 0)), List.of(), "falcon"));

        assertThat(found).extracting(PersonalInfoMatch::kind).containsExactly("TERM");
    }

    @Test
    void nonsenseIsRefused() throws IOException {
        String id = store.store("a.pdf", pdfWith("x"), 1);

        assertThatThrownBy(() -> controller.saveRedactions(id, 0, List.of(new Redaction("r", 1, 1, -5, 10))))
                .isInstanceOf(InvalidEditException.class);
        assertThatThrownBy(() -> controller.saveRedactions(id, 0, List.of(new Redaction(null, 1, 1, 5, 10))))
                .isInstanceOf(InvalidEditException.class);
        assertThatThrownBy(() -> controller.saveRedactions(id, 0, List.of(new Redaction("r", Float.NaN, 1, 5, 10))))
                .isInstanceOf(InvalidEditException.class);
        assertThatThrownBy(() -> controller.saveRedactions(id, 3, List.of()))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> controller.findPersonalInfo(
                new PersonalInfoRequest(List.of(new PageSpec(id, 0, 0)), List.of("NOPE"), null)))
                .isInstanceOf(InvalidEditException.class);
    }

    @Test
    void areasSurviveARestart(@TempDir Path folder) throws IOException {
        PdfDocumentStore saved = new PdfDocumentStore(folder, Duration.ofDays(7));
        String id = saved.store("a.pdf", pdfWith("x"), 1);
        saved.setRedactions(id, 0, List.of(new Redaction("r1", 10, 20, 30, 40)));

        PdfDocumentStore restarted = new PdfDocumentStore(folder, Duration.ofDays(7));

        assertThat(restarted.redactions(id, 0)).containsExactly(new Redaction("r1", 10, 20, 30, 40));
    }
}
