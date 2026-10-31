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
import com.pdfedit.service.PasswordRequiredException;
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
        assertThatThrownBy(() -> controller.upload(pdf("this is not a pdf".getBytes()), null))
                .isInstanceOf(InvalidEditException.class)
                .hasMessageContaining("PDF 파일이 아니거나");
        assertThatThrownBy(() -> controller.upload(pdf(new byte[0]), null)).isInstanceOf(InvalidEditException.class);
    }

    private static byte[] protectedPdf(String userPassword, int pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", userPassword, new AccessPermission());
            policy.setEncryptionKeyLength(256);
            document.protect(policy);
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void aPasswordProtectedPdfAsksForItsPasswordAndSaysWhenItIsWrong() throws IOException {
        byte[] locked = protectedPdf("user", 1);

        assertThatThrownBy(() -> controller.upload(pdf(locked), null))
                .isInstanceOf(PasswordRequiredException.class)
                .hasMessageContaining("암호를 입력");
        assertThatThrownBy(() -> controller.upload(pdf(locked), "nope"))
                .isInstanceOf(PasswordRequiredException.class)
                .hasMessageContaining("맞지 않");
    }

    @Test
    void theRightPasswordOpensThePdfAndKeepsAPlainCopy() throws IOException {
        UploadResponse response = controller.upload(pdf(protectedPdf("user", 2)), "user");

        assertThat(response.unlocked()).isTrue();
        assertThat(response.pageCount()).isEqualTo(2);
        try (PDDocument stored = Loader.loadPDF(store.getContent(response.documentId()))) {
            assertThat(stored.isEncrypted()).isFalse();
            assertThat(stored.getNumberOfPages()).isEqualTo(2);
        }
    }

    @Test
    void aPdfLockedOnlyAgainstChangesOpensWithoutAskingAndIsKeptPlain() throws IOException {
        UploadResponse response = controller.upload(pdf(protectedPdf("", 1)), null);

        assertThat(response.unlocked()).isTrue();
        try (PDDocument stored = Loader.loadPDF(store.getContent(response.documentId()))) {
            assertThat(stored.isEncrypted()).isFalse();
        }
    }

    @Test
    void anOrdinaryPdfIsNotReportedAsUnlocked() throws IOException {
        byte[] plain;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            plain = out.toByteArray();
        }

        assertThat(controller.upload(pdf(plain), "ignored").unlocked()).isFalse();
    }

    private static byte[] restrictedPdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            AccessPermission permissions = new AccessPermission();
            permissions.setCanPrint(false);
            permissions.setCanExtractContent(false);
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "user", permissions);
            policy.setEncryptionKeyLength(256);
            document.protect(policy);
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void theUploadReportsWhatTheOriginalAllowedButNotItsPassword() throws IOException {
        UploadResponse response = controller.upload(pdf(restrictedPdf()), "user");

        assertThat(response.permissions()).isNotNull();
        AccessPermission allowed = new AccessPermission(response.permissions());
        assertThat(allowed.canPrint()).isFalse();
        assertThat(allowed.canExtractContent()).isFalse();
        assertThat(allowed.canModify()).isTrue();
    }

    @Test
    void anOrdinaryPdfHasNoPermissionsToKeep() throws IOException {
        UploadResponse blank = controller.createBlankDocument(200, 200);

        assertThat(blank.permissions()).isNull();
    }

    @Test
    void anExportWithAPasswordIsLockedAgainWithTheOriginalsPermissions() throws IOException {
        UploadResponse upload = controller.upload(pdf(restrictedPdf()), "user");

        byte[] exported = controller.export(new ExportRequest(List.of(new PageSpec(upload.documentId(), 0, 0)),
                "a.pdf", false, "새암호", upload.permissions())).getBody();

        assertThatThrownBy(() -> Loader.loadPDF(exported).close())
                .isInstanceOf(org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException.class);
        try (PDDocument locked = Loader.loadPDF(exported, "새암호")) {
            assertThat(locked.isEncrypted()).isTrue();
            assertThat(locked.getEncryption().getLength()).isEqualTo(256);
            assertThat(locked.getCurrentAccessPermission().isOwnerPermission())
                    .as("the password opens it, it does not lift the restrictions").isFalse();
            AccessPermission allowed = locked.getCurrentAccessPermission();
            assertThat(allowed.canPrint()).isFalse();
            assertThat(allowed.canExtractContent()).isFalse();
        }
    }

    @Test
    void aFlattenedExportCanBeLockedToo() throws IOException {
        UploadResponse blank = controller.createBlankDocument(200, 200);

        byte[] exported = controller.export(new ExportRequest(List.of(new PageSpec(blank.documentId(), 0, 0)),
                "a.pdf", true, "pw", null)).getBody();

        try (PDDocument locked = Loader.loadPDF(exported, "pw")) {
            assertThat(locked.isEncrypted()).isTrue();
            assertThat(locked.getCurrentAccessPermission().canPrint()).as("nothing to keep: all allowed").isTrue();
        }
    }

    @Test
    void anExportWithoutAPasswordStaysOpen() throws IOException {
        UploadResponse upload = controller.upload(pdf(restrictedPdf()), "user");

        byte[] exported = controller.export(new ExportRequest(List.of(new PageSpec(upload.documentId(), 0, 0)),
                "a.pdf", false, "  ", upload.permissions())).getBody();

        try (PDDocument open = Loader.loadPDF(exported)) {
            assertThat(open.isEncrypted()).isFalse();
        }
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
