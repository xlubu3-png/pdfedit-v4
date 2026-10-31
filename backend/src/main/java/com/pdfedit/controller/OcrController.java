package com.pdfedit.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pdfedit.ocr.OcrText;
import com.pdfedit.ocr.PageOcr;
import com.pdfedit.ocr.RecognizedLineBox;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.text.AddedText;

/**
 * Text recognition of scanned pages. What is recognized is kept with the document and written into exports as
 * invisible text, so the exported PDF can be searched and copied.
 */
@RestController
@RequestMapping("/api/v1/pdf/documents/{documentId}/pages/{pageIndex}/ocr")
public class OcrController {

    private final PdfDocumentStore store;
    private final PageOcr pageOcr;
    private final RecognizedLineBox lineBox;

    public OcrController(PdfDocumentStore store, PageOcr pageOcr, RecognizedLineBox lineBox) {
        this.store = store;
        this.pageOcr = pageOcr;
        this.lineBox = lineBox;
    }

    /**
     * @param lines       what the page says, top to bottom
     * @param alreadyText lines not kept because the page already has real text there
     * @param seconds     how long the recognition took
     */
    public record OcrResponse(List<OcrText> lines, int alreadyText, double seconds) {
    }

    /** Recognizes the page (again) and keeps the result. Takes a second or two. */
    @PostMapping
    public OcrResponse recognize(@PathVariable String documentId, @PathVariable int pageIndex) throws IOException {
        checkPage(documentId, pageIndex);
        PageOcr.Result result = pageOcr.recognize(store.getContent(documentId), pageIndex);
        store.setOcr(documentId, pageIndex, result.lines());
        return new OcrResponse(result.lines(), result.alreadyText(), result.seconds());
    }

    /** What was recognized on the page; empty when nothing was. */
    @GetMapping
    public OcrResponse recognized(@PathVariable String documentId, @PathVariable int pageIndex) {
        checkPage(documentId, pageIndex);
        return new OcrResponse(store.ocr(documentId, pageIndex), 0, 0);
    }

    /**
     * A text box that rewrites recognized line {@code line} of the page so it can be edited: the letters over a
     * patch hiding the scanned ones. Nothing is stored; the editor adds the box like any other.
     */
    @PostMapping("/{line}/text-box")
    public AddedText textBox(@PathVariable String documentId, @PathVariable int pageIndex, @PathVariable int line)
            throws IOException {
        checkPage(documentId, pageIndex);
        List<OcrText> lines = store.ocr(documentId, pageIndex);
        if (line < 0 || line >= lines.size()) {
            throw new InvalidEditException("인식한 글자 줄을 찾지 못했습니다. 글자 인식을 다시 해 주세요.");
        }
        return lineBox.of(store.getContent(documentId), pageIndex, lines.get(line));
    }

    /** Drops what was recognized: the page is exported as a plain picture again. */
    @DeleteMapping
    public ResponseEntity<Void> forget(@PathVariable String documentId, @PathVariable int pageIndex) {
        checkPage(documentId, pageIndex);
        store.setOcr(documentId, pageIndex, List.of());
        return ResponseEntity.noContent().build();
    }

    private void checkPage(String documentId, int pageIndex) {
        if (pageIndex < 0 || pageIndex >= store.getInfo(documentId).pageCount()) {
            throw new InvalidEditException("없는 페이지입니다.");
        }
    }
}
