package com.pdfedit.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import com.pdfedit.dto.UploadResponse;
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
