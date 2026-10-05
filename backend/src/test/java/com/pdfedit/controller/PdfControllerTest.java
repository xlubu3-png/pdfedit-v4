package com.pdfedit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.pdfedit.dto.ExportRequest;
import com.pdfedit.dto.PageSpec;
import com.pdfedit.dto.UploadResponse;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.PdfExportService;
import com.pdfedit.service.PdfThumbnailService;
import com.pdfedit.service.TextEditService;
import com.pdfedit.text.FontMatcher;

class PdfControllerTest {

    private final PdfDocumentStore store = new PdfDocumentStore();
    private final TextEditService textEditService = new TextEditService(new FontMatcher());
    private final PdfController controller = new PdfController(
            store, new PdfThumbnailService(), new PdfExportService(store, textEditService), textEditService);

    private static MockMultipartFile pdf(byte[] bytes) {
        return new MockMultipartFile("file", "a.pdf", "application/pdf", bytes);
    }

    @Test
    void uploadingSomethingThatIsNotAPdfIsRefusedWithAReadableMessage() {
        assertThatThrownBy(() -> controller.upload(pdf("this is not a pdf".getBytes())))
                .isInstanceOf(InvalidEditException.class)
                .hasMessageContaining("PDF 파일이 아니거나");
        assertThatThrownBy(() -> controller.upload(pdf(new byte[0]))).isInstanceOf(InvalidEditException.class);
    }

    @Test
    void uploadingAPasswordProtectedPdfSaysSo() throws IOException {
        byte[] protectedPdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "user", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(out);
            protectedPdf = out.toByteArray();
        }

        assertThatThrownBy(() -> controller.upload(pdf(protectedPdf)))
                .isInstanceOf(InvalidEditException.class)
                .hasMessageContaining("암호");
    }

    @Test
    void exportingNoPagesIsRefused() {
        assertThatThrownBy(() -> controller.export(new ExportRequest(null, "a.pdf")))
                .isInstanceOf(InvalidEditException.class);
        assertThatThrownBy(() -> controller.export(new ExportRequest(List.of(), "a.pdf")))
                .isInstanceOf(InvalidEditException.class);
    }

    @Test
    void theExportFileNameCannotBreakOutOfTheHeader() throws IOException {
        UploadResponse blank = controller.createBlankDocument(200, 200);

        var response = controller.export(new ExportRequest(
                List.of(new PageSpec(blank.documentId(), 0, 0)), "a\"b\r\nX-Evil: 1.pdf"));

        String disposition = response.getHeaders().getFirst("Content-Disposition");
        assertThat(disposition).startsWith("attachment; filename=\"").endsWith(".pdf\"");
        assertThat(disposition.substring(22, disposition.length() - 1)).doesNotContain("\"", "\r", "\n");
    }

    @Test
    void anAbsurdThumbnailWidthIsClampedInsteadOfExhaustingMemory() throws IOException {
        UploadResponse blank = controller.createBlankDocument(200, 200);

        byte[] tiny = controller.thumbnail(blank.documentId(), 0, 0);
        byte[] huge = controller.thumbnail(blank.documentId(), 0, 100_000);

        assertThat(tiny).isNotEmpty();
        assertThat(huge).isNotEmpty();
    }

    @Test
    void createBlankDocumentStoresASinglePageDocumentAtTheRequestedSize() throws IOException {
        UploadResponse response = controller.createBlankDocument(300, 400);

        assertThat(response.pageCount()).isEqualTo(1);

        byte[] content = store.getContent(response.documentId());
        try (PDDocument document = Loader.loadPDF(content)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(document.getPage(0).getMediaBox().getWidth()).isEqualTo(300);
            assertThat(document.getPage(0).getMediaBox().getHeight()).isEqualTo(400);
        }
    }
}
