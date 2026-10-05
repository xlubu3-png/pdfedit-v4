package com.pdfedit.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pdfedit.dto.PageTextDto;
import com.pdfedit.dto.TextEditItem;
import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.TextEditService;
import com.pdfedit.text.AddedText;

/** In-place text editing of one page of an uploaded document. */
@RestController
@RequestMapping("/api/v1/pdf/documents/{documentId}/pages/{pageIndex}")
public class TextEditController {

    private final PdfDocumentStore store;
    private final TextEditService textEditService;

    public TextEditController(PdfDocumentStore store, TextEditService textEditService) {
        this.store = store;
        this.textEditService = textEditService;
    }

    @GetMapping("/text-runs")
    public PageTextDto textRuns(@PathVariable String documentId, @PathVariable int pageIndex) throws IOException {
        return textEditService.describePage(store.getContent(documentId), pageIndex,
                store.pageEditsSnapshot(documentId, pageIndex));
    }

    @PutMapping("/text-edits")
    public ResponseEntity<Void> saveEdits(@PathVariable String documentId, @PathVariable int pageIndex,
            @RequestBody List<TextEditItem> items) throws IOException {
        textEditService.recordEdits(store.getContent(documentId), pageIndex,
                store.pageEdits(documentId, pageIndex), items);
        store.persist(documentId);
        return ResponseEntity.noContent().build();
    }

    /** Replaces the text boxes added to the page with the submitted list (the browser owns the list). */
    @PutMapping("/added-texts")
    public ResponseEntity<Void> saveAddedTexts(@PathVariable String documentId, @PathVariable int pageIndex,
            @RequestBody List<AddedText> boxes) throws IOException {
        store.setAddedTexts(documentId, pageIndex,
                textEditService.checkAdded(store.getContent(documentId), pageIndex, boxes));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/text-edits")
    public ResponseEntity<Void> resetEdits(@PathVariable String documentId, @PathVariable int pageIndex) {
        store.clearPageEdits(documentId, pageIndex);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/preview", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] preview(@PathVariable String documentId, @PathVariable int pageIndex,
            @RequestParam(defaultValue = "150") int dpi) throws IOException {
        return textEditService.renderPreview(store.getContent(documentId), pageIndex,
                store.pageEditsSnapshot(documentId, pageIndex), Math.max(72, Math.min(300, dpi)));
    }
}
