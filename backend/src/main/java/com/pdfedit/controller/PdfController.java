package com.pdfedit.controller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
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
import com.pdfedit.dto.ImageExportRequest;
import com.pdfedit.dto.UploadResponse;
import com.pdfedit.ocr.PageOcr;
import com.pdfedit.redact.Redaction;
import com.pdfedit.redact.Redactor;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.OfficeConverter;
import com.pdfedit.service.PageCopies;
import com.pdfedit.service.PageEdits;
import com.pdfedit.service.PageImages;
import com.pdfedit.service.PageRotationBaker;
import com.pdfedit.service.PageWarmer;
import com.pdfedit.service.PasswordRequiredException;
import com.pdfedit.service.PdfProtector;
import com.pdfedit.service.PdfUnlocker;
import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.PdfExportService;
import com.pdfedit.service.PdfThumbnailService;
import com.pdfedit.service.TextEditService;
import com.pdfedit.text.PageStraightener;

@RestController
@RequestMapping("/api/v1/pdf")
public class PdfController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PdfController.class);

    static final String REDACTION_HEADER = "X-Redaction-Report";
    /** "pictures=12;before=24000000;after=3100000": what storing the pictures smaller saved (용량 줄이기). */
    static final String SHRINK_HEADER = "X-Shrink-Report";
    private static final int MIN_THUMBNAIL_WIDTH = 16;
    private static final int MAX_THUMBNAIL_WIDTH = 2000;
    /** Pages looked at to tell a scan; enough, and quick even for a long document. */
    private static final int SCAN_CHECK_PAGES = 5;
    private static final float MIN_PAGE_SIDE = 3;
    private static final float MAX_PAGE_SIDE = 14_400;

    private final PdfDocumentStore store;
    private final PdfThumbnailService thumbnailService;
    private final PdfExportService exportService;
    private final TextEditService textEditService;
    /** Started in the background when a scan is opened; none in tests that build the controller by hand. */
    private PageOcr pageOcr;

    public PdfController(PdfDocumentStore store, PdfThumbnailService thumbnailService,
            PdfExportService exportService, TextEditService textEditService) {
        this.store = store;
        this.thumbnailService = thumbnailService;
        this.exportService = exportService;
        this.textEditService = textEditService;
    }

    @Autowired(required = false)
    void setPageOcr(PageOcr pageOcr) {
        this.pageOcr = pageOcr;
    }

    /** Reads a document's pages ahead once it is opened, so the editor shows them at once. */
    private PageWarmer pageWarmer;
    /** Prints Word, Excel and PowerPoint files to PDF with the PC's Office. */
    private OfficeConverter officeConverter;

    @Autowired(required = false)
    void setOfficeConverter(OfficeConverter officeConverter) {
        this.officeConverter = officeConverter;
    }

    @Autowired(required = false)
    void setPageWarmer(PageWarmer pageWarmer) {
        this.pageWarmer = pageWarmer;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(@RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password) throws IOException {
        String fileName = file.getOriginalFilename();
        byte[] uploaded = file.getBytes();
        String convertedFrom = null;
        if (OfficeConverter.isOffice(fileName)) {
            // A Word, Excel or PowerPoint file: printed to PDF by the PC's Office, then opened like any PDF.
            if (officeConverter == null) {
                throw new InvalidEditException("Office 문서를 열 수 없습니다.");
            }
            uploaded = officeConverter.toPdf(uploaded, fileName);
            convertedFrom = fileName;
            fileName = OfficeConverter.pdfName(fileName);
        }
        byte[] content;
        int pageCount;
        boolean unlocked;
        Integer permissions;
        boolean scanned;
        try {
            // An encrypted file is kept as a plain copy, so nothing after this line needs the password.
            PdfUnlocker.Unlocked plain = PdfUnlocker.unlock(uploaded, password);
            unlocked = plain.wasEncrypted();
            permissions = plain.permissions();
            // Pages that carry a /Rotate are turned into unrotated ones once, here, so editing never has to care.
            content = PageRotationBaker.bake(plain.content());
            try (PDDocument document = Loader.loadPDF(content)) {
                pageCount = document.getNumberOfPages();
                scanned = pageCount > 0 && hasNoText(document);
            }
        } catch (InvalidPasswordException e) {
            throw new PasswordRequiredException(password == null || password.isEmpty()
                    ? "암호가 걸린 PDF입니다. 암호를 입력해 주세요."
                    : "암호가 맞지 않습니다. 다시 입력해 주세요.");
        } catch (IOException e) {
            throw new InvalidEditException("PDF 파일이 아니거나 손상되어 열 수 없습니다.");
        }
        if (pageCount == 0) {
            throw new InvalidEditException("페이지가 없는 PDF입니다.");
        }
        String documentId = store.store(fileName, content, pageCount);
        if (scanned && pageOcr != null) {
            // The screen offers text recognition for a scan: the engine gets ready while the user decides.
            pageOcr.prepareInBackground();
        }
        if (pageWarmer != null && !scanned) {
            // A scan has no text to read ahead; its pages are recognized instead.
            pageWarmer.wholeDocument(documentId, pageCount);
        }
        return new UploadResponse(documentId, fileName, pageCount, unlocked, permissions, scanned, convertedFrom);
    }

    /** No text on its first pages: a scan, most likely, that the screen offers to run text recognition on. */
    private static boolean hasNoText(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setEndPage(Math.min(document.getNumberOfPages(), SCAN_CHECK_PAGES));
        return stripper.getText(document).isBlank();
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
        // PDF readers accept pages of 3 to 14,400 units a side; outside that (or NaN) the page is unusable.
        if (!(width >= MIN_PAGE_SIDE && width <= MAX_PAGE_SIDE && height >= MIN_PAGE_SIDE && height <= MAX_PAGE_SIDE)) {
            throw new InvalidEditException("페이지 크기는 " + (int) MIN_PAGE_SIDE + "~" + (int) MAX_PAGE_SIDE
                    + "pt 사이여야 합니다.");
        }
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

    /**
     * A copy of one page as a document of its own, with everything done to the page so far; edited after
     * this, the copy and the original go their own ways.
     */
    @PostMapping("/documents/{documentId}/pages/{pageIndex}/duplicate")
    public UploadResponse duplicatePage(@PathVariable String documentId, @PathVariable int pageIndex)
            throws IOException {
        PdfDocumentStore.DocumentInfo info = store.getInfo(documentId);
        if (pageIndex < 0 || pageIndex >= info.pageCount()) {
            throw new InvalidEditException("없는 페이지는 복제할 수 없습니다.");
        }
        byte[] content;
        try (PDDocument source = Loader.loadPDF(store.getContent(documentId));
                PDDocument copy = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy.importPage(source.getPage(pageIndex));
            // The page's form fields and links come along, the rest of the document does not; its bookmarks stay
            // with the original (exported together, the two would each bring them).
            PageCopies copies = new PageCopies();
            copies.add(source, source.getPage(pageIndex));
            copies.finish(copy, false, true);
            copy.save(out);
            content = out.toByteArray();
        }
        String copyId = store.store(info.fileName(), content, 1);
        store.copyPageEdits(documentId, pageIndex, copyId, 0);
        return new UploadResponse(copyId, info.fileName(), 1);
    }

    /**
     * @param documentId the straightened page as a document of its own (one page), or null when the page was left
     *                   as it is (upright and straight already, its slant not told, or not a scan)
     * @param degrees    how far it was turned clockwise to lie straight (a sheet fed askew)
     * @param quarter    quarter turns clockwise it was set upright by first (on its side: 90, 270; upside down: 180)
     */
    public record StraightenResponse(String documentId, double degrees, int quarter) {
    }

    /**
     * A scanned page set upright and straight: scanned on its side or upside down it is turned the right way up (as
     * the text reader tells), and fed askew it is turned back by the slant measured on it. A copy is made; what was
     * recognized on the page is not kept (its places were the old ones), the page is to be recognized again.
     *
     * @param shown how the page list shows the page turned (0, 90, 180, 270): the user turned it already, so its way
     *              up is theirs and only its slant is set straight
     */
    @PostMapping("/documents/{documentId}/pages/{pageIndex}/straighten")
    public StraightenResponse straightenPage(@PathVariable String documentId, @PathVariable int pageIndex,
            @RequestParam(defaultValue = "0") int shown) throws IOException {
        PdfDocumentStore.DocumentInfo info = store.getInfo(documentId);
        if (pageIndex < 0 || pageIndex >= info.pageCount()) {
            throw new InvalidEditException("없는 페이지는 바로잡을 수 없습니다.");
        }
        byte[] pdf = store.getContent(documentId);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            if (!PageStraightener.isScan(doc, pageIndex)) {
                return new StraightenResponse(null, 0, 0);
            }
        }
        long t0 = System.nanoTime();
        int quarter = 0;
        if (shown % 360 == 0 && pageOcr != null) {
            try {
                quarter = pageOcr.uprightTurn(pdf, pageIndex);
            } catch (com.pdfedit.ocr.OcrUnavailableException e) {
                // Without the reader the way up cannot be told; the slant still can.
            }
        }
        byte[] page = pdf;
        int index = pageIndex;
        if (quarter != 0) {
            page = PageRotationBaker.bake(PageStraightener.quarterTurned(pdf, pageIndex, quarter));
            index = 0;
        }
        long t1 = System.nanoTime();
        double degrees;
        try (PDDocument doc = Loader.loadPDF(page)) {
            degrees = PageStraightener.angle(doc, index);
        }
        long t2 = System.nanoTime();
        if (quarter == 0 && degrees == 0) {
            return new StraightenResponse(null, 0, 0);
        }
        byte[] straight = degrees == 0 ? page : PageStraightener.straightened(page, index, degrees);
        long t3 = System.nanoTime();
        String copyId = store.store(info.fileName(), straight, 1);
        log.info("기울기 바로잡기 {}쪽: 방향 {} ms, 기울기 {} ms, 돌리기 {} ms, 저장 {} ms", pageIndex + 1,
                (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, (System.nanoTime() - t3) / 1_000_000);
        if (quarter == 0) {
            // Turned a little, the page's edits still sit where they were; turned a quarter or more they would not.
            store.copyPageEdits(documentId, pageIndex, copyId, 0);
        }
        store.setOcr(copyId, 0, List.of());
        return new StraightenResponse(copyId, degrees, quarter);
    }

    @GetMapping(value = "/documents/{documentId}/pages/{pageIndex}/thumbnail", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] thumbnail(@PathVariable String documentId, @PathVariable int pageIndex,
            @RequestParam(defaultValue = "240") int width) throws IOException {
        // A picture of absurd size would exhaust memory; the page list never asks for more than a few hundred.
        int shown = Math.max(MIN_THUMBNAIL_WIDTH, Math.min(MAX_THUMBNAIL_WIDTH, width));
        byte[] content = store.getContent(documentId);
        PageEdits edits = store.pageEditsSnapshot(documentId, pageIndex);
        List<Redaction> redactions = store.redactions(documentId, pageIndex);
        if (edits.isEmpty() && redactions.isEmpty()) {
            return thumbnailService.renderPage(content, pageIndex, shown);
        }
        byte[] page = textEditService.buildSinglePage(content, pageIndex, edits);
        if (!redactions.isEmpty()) {
            // The areas only painted: the real redaction is done on export.
            try (PDDocument single = Loader.loadPDF(page)) {
                Redactor.paint(single, single.getPage(0), redactions);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                single.save(out);
                page = out.toByteArray();
            }
        }
        return thumbnailService.renderPage(page, 0, shown);
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody ExportRequest request) throws IOException {
        if (request.pages() == null || request.pages().isEmpty()) {
            throw new InvalidEditException("내보낼 페이지가 없습니다.");
        }
        PdfExportService.Exported result = exportService.exportWithReport(request.pages(),
                Boolean.TRUE.equals(request.flatten()), Boolean.TRUE.equals(request.compress()));
        // Quotes, backslashes and control characters would break out of the header's quoted file name.
        String requested = request.fileName() == null ? "" : request.fileName().replaceAll("[\\p{Cntrl}\"\\\\]", "_").trim();
        String fileName = requested.isEmpty() ? "edited.pdf" : requested;
        byte[] pdf = request.password() == null || request.password().isBlank() ? result.pdf()
                : PdfProtector.lock(result.pdf(), request.password(), request.permissions());

        return ResponseEntity.ok()
                // filename*=UTF-8''... as well, so a Korean file name arrives intact instead of as question marks.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .header(REDACTION_HEADER, redactionReport(result.redaction()))
                .header(SHRINK_HEADER, "pictures=" + result.shrink().pictures() + ";before=" + result.shrink().before()
                        + ";after=" + result.shrink().after())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /**
     * 사진으로 내보내기: the pages as they would be exported (edits, redaction, rotation) drawn as pictures - one
     * picture for one page, a ZIP of them for more.
     */
    @PostMapping("/export/images")
    public ResponseEntity<byte[]> exportImages(@RequestBody ImageExportRequest request) throws IOException {
        if (request.pages() == null || request.pages().isEmpty()) {
            throw new InvalidEditException("내보낼 페이지가 없습니다.");
        }
        PdfExportService.Exported result = exportService.exportWithReport(request.pages(), false, false);
        // The name goes into the ZIP's entries as well: no folders, nothing Windows refuses in a file name.
        String requested = request.fileName() == null ? ""
                : request.fileName().replaceAll("[\\p{Cntrl}\"\\\\/:*?<>|]", "_").replaceAll("(?i)\\.pdf$", "").trim();
        String stem = requested.isEmpty() || requested.chars().allMatch(c -> c == '.') ? "pages" : requested;
        PageImages.Images images = PageImages.render(result.pdf(), PageImages.Format.of(request.format()),
                request.dpi() == null ? PageImages.DEFAULT_DPI : request.dpi(), stem);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(images.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(REDACTION_HEADER, redactionReport(result.redaction()))
                .contentType(MediaType.parseMediaType(images.contentType()))
                .body(images.content());
    }

    /** e.g. "areas=3;removed=41;pictures=2,5": what the screen reports after the download. */
    private static String redactionReport(PdfExportService.RedactionReport redaction) {
        return "areas=" + redaction.areas() + ";removed=" + redaction.glyphsRemoved() + ";pictures="
                + redaction.picturePages().stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
