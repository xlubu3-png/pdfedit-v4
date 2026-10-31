package com.pdfedit.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pdfedit.compare.DocumentComparer;
import com.pdfedit.compare.VisualDiff;
import com.pdfedit.dto.CompareResult;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.PdfThumbnailService;
import com.pdfedit.ocr.OcrText;
import com.pdfedit.text.TextLines;

/**
 * Compares two uploaded documents as they were uploaded (the edits made in this app are not part of it):
 * the text that changed, and on request where a pair of pages looks different.
 */
@RestController
@RequestMapping("/api/v1/pdf")
public class CompareController {

    static final int MAX_PAGES = 500;
    private static final int MAX_IMAGE_WIDTH = 2000;

    private final PdfDocumentStore store;
    private final PdfThumbnailService thumbnailService;

    public CompareController(PdfDocumentStore store, PdfThumbnailService thumbnailService) {
        this.store = store;
        this.thumbnailService = thumbnailService;
    }

    public record CompareRequest(String a, String b) {
    }

    /** Where a pair of pages looks different; {@code comparable} is false when they differ in size. */
    public record VisualResult(boolean comparable, List<TextLines.Area> areas) {
    }

    @PostMapping("/compare")
    public CompareResult compare(@RequestBody CompareRequest request) throws IOException {
        if (request.a() == null || request.b() == null) {
            throw new InvalidEditException("비교할 문서 두 개를 고르세요.");
        }
        PdfDocumentStore.DocumentInfo infoA = store.getInfo(request.a());
        PdfDocumentStore.DocumentInfo infoB = store.getInfo(request.b());
        if (infoA.pageCount() > MAX_PAGES || infoB.pageCount() > MAX_PAGES) {
            throw new InvalidEditException("비교는 " + MAX_PAGES + "쪽 이하인 문서끼리만 할 수 있습니다.");
        }
        try (PDDocument a = Loader.loadPDF(store.getContent(request.a()));
                PDDocument b = Loader.loadPDF(store.getContent(request.b()))) {
            // Scanned pages compare by what text recognition read off them.
            DocumentComparer.Comparison result = DocumentComparer.compare(a, b, recognized(request.a()),
                    recognized(request.b()));
            return new CompareResult(request.a(), request.b(), infoA.fileName(), infoB.fileName(), sizes(a), sizes(b),
                    result.removed(), result.added(), result.removedWords(), result.addedWords());
        }
    }

    private IntFunction<List<TextLines.Line>> recognized(String documentId) {
        return page -> store.ocr(documentId, page).stream().map(OcrText::line).toList();
    }

    @GetMapping("/compare/visual")
    public VisualResult visual(@RequestParam String a, @RequestParam int pageA, @RequestParam String b,
            @RequestParam int pageB) throws IOException {
        try (PDDocument docA = Loader.loadPDF(store.getContent(a)); PDDocument docB = Loader.loadPDF(store.getContent(b))) {
            List<TextLines.Area> areas = VisualDiff.areas(docA, pageA, docB, pageB);
            return areas == null ? new VisualResult(false, List.of()) : new VisualResult(true, areas);
        }
    }

    /** A picture of the page as it was uploaded, without the edits made to it. */
    @GetMapping(value = "/documents/{documentId}/pages/{pageIndex}/original", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] original(@PathVariable String documentId, @PathVariable int pageIndex,
            @RequestParam(defaultValue = "800") int width) throws IOException {
        return thumbnailService.renderPage(store.getContent(documentId), pageIndex,
                Math.max(16, Math.min(MAX_IMAGE_WIDTH, width)));
    }

    private static List<CompareResult.PageSize> sizes(PDDocument doc) {
        List<CompareResult.PageSize> sizes = new ArrayList<>();
        for (PDPage page : doc.getPages()) {
            PDRectangle crop = page.getCropBox();
            sizes.add(new CompareResult.PageSize(crop.getWidth(), crop.getHeight(), crop.getLowerLeftX(),
                    crop.getLowerLeftY()));
        }
        return sizes;
    }
}
