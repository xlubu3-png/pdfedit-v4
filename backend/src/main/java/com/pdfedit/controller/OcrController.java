package com.pdfedit.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** A corrected line holds at most this many characters. */
    private static final int MAX_LINE = 1000;

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
     * @param turn        when the way up was to be told first and the page is upside down or on its side: the
     *                    quarter turns clockwise that set it upright (90, 180, 270), and nothing was read or kept
     */
    public record OcrResponse(List<OcrText> lines, int alreadyText, double seconds, int turn) {
        public OcrResponse(List<OcrText> lines, int alreadyText, double seconds) {
            this(lines, alreadyText, seconds, 0);
        }
    }

    /**
     * Recognizes the page (again) and keeps the result. Takes a second or two. The page's font, which rewriting its
     * lines needs, is then told in the background.
     */
    @PostMapping
    public OcrResponse recognize(@PathVariable String documentId, @PathVariable int pageIndex,
            @RequestParam(defaultValue = "false") boolean wayUp) throws IOException {
        checkPage(documentId, pageIndex);
        byte[] pdf = store.getContent(documentId);
        PageOcr.Result result = pageOcr.recognize(pdf, pageIndex, wayUp);
        if (result.turn() != 0) {
            // Not upright: nothing kept; the screen turns the page (straighten with this turn) and reads it again.
            return new OcrResponse(List.of(), 0, result.seconds(), result.turn());
        }
        store.setOcr(documentId, pageIndex, result.lines());
        lineBox.prepare(pdf, pageIndex, result.lines());
        return new OcrResponse(result.lines(), result.alreadyText(), result.seconds());
    }

    /** What was recognized on the page; empty when nothing was. Its font is told in the background as well. */
    @GetMapping
    public OcrResponse recognized(@PathVariable String documentId, @PathVariable int pageIndex) {
        checkPage(documentId, pageIndex);
        List<OcrText> lines = store.ocr(documentId, pageIndex);
        lineBox.prepare(store.getContent(documentId), pageIndex, lines);
        return new OcrResponse(lines, 0, 0);
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
        return lineBox.of(store.getContent(documentId), pageIndex, lines.get(line), lines);
    }

    /** What a recognized line should say instead of what recognition read off it. */
    public record Correction(String text) {
    }

    /**
     * Corrects what recognition read off line {@code line}: the scan stays as it is, and the line says the corrected
     * text in search, copying and the export's invisible text. A corrected line is sure (no longer marked to check).
     */
    @PutMapping("/{line}")
    public OcrText correct(@PathVariable String documentId, @PathVariable int pageIndex, @PathVariable int line,
            @RequestBody Correction correction) {
        checkPage(documentId, pageIndex);
        List<OcrText> lines = store.ocr(documentId, pageIndex);
        if (line < 0 || line >= lines.size()) {
            throw new InvalidEditException("인식한 글자 줄을 찾지 못했습니다. 글자 인식을 다시 해 주세요.");
        }
        String text = correction == null || correction.text() == null ? "" : correction.text().strip();
        if (text.isEmpty() || text.contains("\n") || text.length() > MAX_LINE) {
            throw new InvalidEditException("고칠 글자는 한 줄로, " + MAX_LINE + "자까지 써 주세요.");
        }
        OcrText old = lines.get(line);
        // The letters' places still hold for as many letters; for another count they are spread evenly.
        List<Float> centers = old.centers() != null && old.centers().size() == text.length() ? old.centers() : null;
        OcrText corrected = new OcrText(text, old.x(), old.y(), old.width(), old.height(), 1f, centers,
                old.orientation(), old.tilt());
        List<OcrText> next = new java.util.ArrayList<>(lines);
        next.set(line, corrected);
        store.setOcr(documentId, pageIndex, next);
        return corrected;
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
