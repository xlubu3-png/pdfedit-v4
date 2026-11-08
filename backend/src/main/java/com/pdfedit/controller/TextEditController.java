package com.pdfedit.controller;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
import com.pdfedit.dto.TableGrid;
import com.pdfedit.dto.TextEditItem;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PageWarmer;
import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.TextEditService;
import com.pdfedit.text.AddedText;
import com.pdfedit.text.RulingLines;
import com.pdfedit.text.ScanRulings;

/** In-place text editing of one page of an uploaded document. */
@RestController
@RequestMapping("/api/v1/pdf/documents/{documentId}/pages/{pageIndex}")
public class TextEditController {

    /** Most tables set out by hand on one page. */
    private static final int MAX_GRIDS = 50;

    private final PdfDocumentStore store;
    private final TextEditService textEditService;

    /** Reads the pages next to the one opened ahead; null in tests. */
    private final PageWarmer pageWarmer;

    public TextEditController(PdfDocumentStore store, TextEditService textEditService) {
        this(store, textEditService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TextEditController(PdfDocumentStore store, TextEditService textEditService, PageWarmer pageWarmer) {
        this.store = store;
        this.textEditService = textEditService;
        this.pageWarmer = pageWarmer;
    }

    @GetMapping("/text-runs")
    public PageTextDto textRuns(@PathVariable String documentId, @PathVariable int pageIndex) throws IOException {
        if (pageWarmer != null) {
            // The editor is on this page: the ones the user turns to next are read meanwhile.
            pageWarmer.read(documentId, pageIndex);
            pageWarmer.around(documentId, pageIndex, store.getInfo(documentId).pageCount());
        }
        return textEditService.describePage(store.getContent(documentId), pageIndex,
                store.pageEditsSnapshot(documentId, pageIndex));
    }

    /**
     * The page's table borders (straight lines drawn on it), in points from the top-left of its visible area: with
     * them, text copied from a table goes back into its cells ("표 모양으로 복사").
     */
    @GetMapping("/rulings")
    public RulingLines.Lines rulings(@PathVariable String documentId, @PathVariable int pageIndex) throws IOException {
        try (PDDocument document = Loader.loadPDF(store.getContent(documentId))) {
            if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
                throw new InvalidEditException("페이지 번호가 잘못되었습니다.");
            }
            RulingLines.Lines drawn = RulingLines.of(document.getPage(pageIndex));
            // No table drawn with lines: a scan's table is only in the picture, its borders are looked for there.
            if (drawn.horizontal().size() < 2 || drawn.vertical().size() < 2) {
                // Where text was recognized, a short straight stroke is part of a letter, not a cell's side.
                PDRectangle crop = document.getPage(pageIndex).getCropBox();
                List<float[]> words = store.ocr(documentId, pageIndex).stream()
                        .map(t -> new float[] {t.x() - crop.getLowerLeftX(), crop.getUpperRightY() - (t.y() + t.height()),
                                t.width(), t.height()})
                        .toList();
                RulingLines.Lines scanned = ScanRulings.of(document, pageIndex, words);
                if (scanned.horizontal().size() >= 2 && scanned.vertical().size() >= 2) {
                    return scanned;
                }
            }
            return drawn;
        }
    }

    /** How big the page is (points, its visible area): the editor shows its picture before its lines are read. */
    public record PageSize(float width, float height) {
    }

    @GetMapping("/size")
    public PageSize size(@PathVariable String documentId, @PathVariable int pageIndex) throws IOException {
        try (PDDocument document = Loader.loadPDF(store.getContent(documentId))) {
            if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
                throw new InvalidEditException("페이지 번호가 잘못되었습니다.");
            }
            PDRectangle crop = document.getPage(pageIndex).getCropBox();
            return new PageSize(crop.getWidth(), crop.getHeight());
        }
    }

    /** The tables set out by hand on the page (표 칸 바로잡기); empty when the page's own are used. */
    @GetMapping("/table-grids")
    public List<TableGrid> tableGrids(@PathVariable String documentId, @PathVariable int pageIndex) {
        checkPage(documentId, pageIndex);
        return store.tableGrids(documentId, pageIndex);
    }

    /** Replaces the tables set out by hand on the page; an empty list goes back to the page's own tables. */
    @PutMapping("/table-grids")
    public ResponseEntity<Void> saveTableGrids(@PathVariable String documentId, @PathVariable int pageIndex,
            @RequestBody List<TableGrid> grids) {
        checkPage(documentId, pageIndex);
        if (grids.size() > MAX_GRIDS || grids.stream().anyMatch(g -> g == null || !g.valid())) {
            throw new InvalidEditException("표의 칸 선이 잘못되었습니다.");
        }
        store.setTableGrids(documentId, pageIndex, grids);
        return ResponseEntity.noContent().build();
    }

    private void checkPage(String documentId, int pageIndex) {
        int pages = store.getInfo(documentId).pageCount();
        if (pageIndex < 0 || pageIndex >= pages) {
            throw new InvalidEditException("페이지 번호가 잘못되었습니다.");
        }
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

    /** The page as it now looks: a JPEG for a page of photographs (much smaller), else a PNG (sharp letters). */
    @GetMapping(value = "/preview", produces = {MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE})
    public ResponseEntity<byte[]> preview(@PathVariable String documentId, @PathVariable int pageIndex,
            @RequestParam(defaultValue = "150") int dpi) throws IOException {
        byte[] picture = textEditService.renderPreview(store.getContent(documentId), pageIndex,
                store.pageEditsSnapshot(documentId, pageIndex), Math.max(72, Math.min(300, dpi)));
        boolean jpeg = picture.length > 2 && (picture[0] & 0xff) == 0xff && (picture[1] & 0xff) == 0xd8;
        return ResponseEntity.ok().contentType(jpeg ? MediaType.IMAGE_JPEG : MediaType.IMAGE_PNG).body(picture);
    }
}
