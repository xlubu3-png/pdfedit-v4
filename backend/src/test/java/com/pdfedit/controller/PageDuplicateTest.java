package com.pdfedit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import com.pdfedit.dto.UploadResponse;
import com.pdfedit.redact.Redaction;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PageEdits;
import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.PdfExportService;
import com.pdfedit.service.PdfThumbnailService;
import com.pdfedit.service.TextEditService;
import com.pdfedit.text.AddedImage;
import com.pdfedit.text.AddedText;
import com.pdfedit.text.FontMatcher;
import com.pdfedit.text.RunEdit;

/** A duplicated page is a page of its own: it starts with everything done to the original, then goes its own way. */
class PageDuplicateTest {

    private final PdfDocumentStore store = new PdfDocumentStore();
    private final TextEditService textEditService = new TextEditService(new FontMatcher());
    private final PdfController controller = new PdfController(
            store, new PdfThumbnailService(), new PdfExportService(store, textEditService), textEditService);

    private String twoPageDocument() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.addPage(new PDPage(new PDRectangle(300, 200)));
            document.save(out);
            return store.store("계약서.pdf", out.toByteArray(), 2);
        }
    }

    @Test
    void theCopyIsTheSamePageWithTheSameEdits() throws IOException {
        String id = twoPageDocument();
        store.pageEdits(id, 1).put(3, new RunEdit("고친 글", null, null, null, null, 0, 0, null));
        AddedText note = new AddedText("t1", "덧붙인 글", 10, 20, "맑은 고딕", 12, false, "#000000", List.of());
        store.setAddedTexts(id, 1, List.of(note));
        byte[] stamp = {1, 2, 3};
        String imageId = store.storeImage(id, stamp);
        store.setAddedImages(id, 1, List.of(new AddedImage("i1", imageId, 5, 5, 50, 50, true)));
        Redaction hidden = new Redaction("r1", 30, 40, 50, 10);
        store.setRedactions(id, 1, List.of(hidden));

        UploadResponse copy = controller.duplicatePage(id, 1);

        assertThat(copy.documentId()).isNotEqualTo(id);
        assertThat(copy.fileName()).isEqualTo("계약서.pdf");
        assertThat(copy.pageCount()).isEqualTo(1);
        try (PDDocument single = Loader.loadPDF(store.getContent(copy.documentId()))) {
            assertThat(single.getNumberOfPages()).isEqualTo(1);
            assertThat(single.getPage(0).getMediaBox().getWidth()).isEqualTo(300);
        }
        PageEdits copied = store.pageEditsSnapshot(copy.documentId(), 0);
        assertThat(copied.runs()).containsOnlyKeys(3);
        assertThat(copied.runs().get(3).text()).isEqualTo("고친 글");
        assertThat(copied.added()).containsExactly(note);
        assertThat(copied.images()).singleElement().satisfies(placed -> {
            assertThat(placed.data()).isEqualTo(stamp);
            assertThat(placed.box().id()).isEqualTo("i1");
        });
        assertThat(store.redactions(copy.documentId(), 0)).containsExactly(hidden);
    }

    @Test
    void editingTheCopyLeavesTheOriginalAlone() throws IOException {
        String id = twoPageDocument();
        store.pageEdits(id, 0).put(0, new RunEdit("원본", null, null, null, null, 0, 0, null));

        UploadResponse copy = controller.duplicatePage(id, 0);
        store.pageEdits(copy.documentId(), 0).put(0, new RunEdit("사본", null, null, null, null, 0, 0, null));
        store.setRedactions(copy.documentId(), 0, List.of(new Redaction("r", 1, 1, 5, 5)));

        assertThat(store.pageEditsSnapshot(id, 0).runs().get(0).text()).isEqualTo("원본");
        assertThat(store.redactions(id, 0)).isEmpty();
    }

    @Test
    void aPageThatIsNotThereCannotBeDuplicated() throws IOException {
        String id = twoPageDocument();

        assertThatThrownBy(() -> controller.duplicatePage(id, 2)).isInstanceOf(InvalidEditException.class);
        assertThatThrownBy(() -> controller.duplicatePage(id, -1)).isInstanceOf(InvalidEditException.class);
    }
}
