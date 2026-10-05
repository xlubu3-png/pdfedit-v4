package com.pdfedit.controller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.pdfedit.dto.ExportRequest;
import com.pdfedit.dto.UploadResponse;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PageEdits;
import com.pdfedit.service.PageRotationBaker;
import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.PdfExportService;
import com.pdfedit.service.PdfThumbnailService;
import com.pdfedit.service.TextEditService;

@RestController
@RequestMapping("/api/v1/pdf")
public class PdfController {

    private static final int MIN_THUMBNAIL_WIDTH = 16;
    private static final int MAX_THUMBNAIL_WIDTH = 2000;

    private final PdfDocumentStore store;
    private final PdfThumbnailService thumbnailService;
    private final PdfExportService exportService;
    private final TextEditService textEditService;

    public PdfController(PdfDocumentStore store, PdfThumbnailService thumbnailService,
            PdfExportService exportService, TextEditService textEditService) {
        this.store = store;
        this.thumbnailService = thumbnailService;
        this.exportService = exportService;
        this.textEditService = textEditService;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        byte[] content;
        int pageCount;
        try {
            // Pages that carry a /Rotate are turned into unrotated ones once, here, so editing never has to care.
            content = PageRotationBaker.bake(file.getBytes());
            try (PDDocument document = Loader.loadPDF(content)) {
                pageCount = document.getNumberOfPages();
            }
        } catch (InvalidPasswordException e) {
            throw new InvalidEditException("암호가 걸린 PDF는 열 수 없습니다. 암호를 해제한 파일을 올려 주세요.");
        } catch (IOException e) {
            throw new InvalidEditException("PDF 파일이 아니거나 손상되어 열 수 없습니다.");
        }
        if (pageCount == 0) {
            throw new InvalidEditException("페이지가 없는 PDF입니다.");
        }
        String documentId = store.store(file.getOriginalFilename(), content, pageCount);
        return new UploadResponse(documentId, file.getOriginalFilename(), pageCount);
    }

    /** What the browser needs to pick a document up again after a reload: its name and page count. */
    @GetMapping("/documents/{documentId}")
    public UploadResponse documentInfo(@PathVariable String documentId) {
        PdfDocumentStore.DocumentInfo info = store.getInfo(documentId);
        return new UploadResponse(documentId, info.fileName(), info.pageCount());
    }

    /** Removes the stored copy of a document and its edits, e.g. when the user starts over. */
    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Void> deleteDocument(@PathVariable String documentId) {
        store.delete(documentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/documents/blank")
    public UploadResponse createBlankDocument(
            @RequestParam(defaultValue = "595") float width,
            @RequestParam(defaultValue = "842") float height) throws IOException {
        byte[] content;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(width, height)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            content = out.toByteArray();
        }
        String documentId = store.store("빈 페이지", content, 1);
        return new UploadResponse(documentId, "빈 페이지", 1);
    }

    @GetMapping(value = "/documents/{documentId}/pages/{pageIndex}/thumbnail", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] thumbnail(@PathVariable String documentId, @PathVariable int pageIndex,
            @RequestParam(defaultValue = "240") int width) throws IOException {
        // A picture of absurd size would exhaust memory; the page list never asks for more than a few hundred.
        int shown = Math.max(MIN_THUMBNAIL_WIDTH, Math.min(MAX_THUMBNAIL_WIDTH, width));
        byte[] content = store.getContent(documentId);
        PageEdits edits = store.pageEditsSnapshot(documentId, pageIndex);
        if (edits.isEmpty()) {
            return thumbnailService.renderPage(content, pageIndex, shown);
        }
        return thumbnailService.renderPage(textEditService.buildSinglePage(content, pageIndex, edits), 0, shown);
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody ExportRequest request) throws IOException {
        if (request.pages() == null || request.pages().isEmpty()) {
            throw new InvalidEditException("내보낼 페이지가 없습니다.");
        }
        byte[] result = exportService.export(request.pages(), Boolean.TRUE.equals(request.flatten()));
        // Quotes, backslashes and control characters would break out of the header's quoted file name.
        String requested = request.fileName() == null ? "" : request.fileName().replaceAll("[\\p{Cntrl}\"\\\\]", "_").trim();
        String fileName = requested.isEmpty() ? "edited.pdf" : requested;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(result);
    }
}
