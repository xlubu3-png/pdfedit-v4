package com.pdfedit.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import com.pdfedit.dto.PageSpec;
import com.pdfedit.ocr.OcrText;
import com.pdfedit.ocr.OcrTextLayer;
import com.pdfedit.redact.Redaction;
import com.pdfedit.redact.Redactor;

/**
 * Builds the final PDF from an ordered list of {@link PageSpec}s, each
 * pointing at a page in one of the previously uploaded source documents.
 * Text edits recorded for a source page travel with it into the output.
 */
@Service
public class PdfExportService {

    private static final float FLATTEN_DPI = 200f;

    private final PdfDocumentStore store;
    private final TextEditService textEditService;

    public PdfExportService(PdfDocumentStore store, TextEditService textEditService) {
        this.store = store;
        this.textEditService = textEditService;
    }

    /**
     * What the redaction did on export.
     *
     * @param areas          areas redacted, over all pages
     * @param glyphsRemoved  letters cut out of the pages
     * @param picturePages   pages (1-based, in the output) that had to be made pictures to be sure
     */
    public record RedactionReport(int areas, int glyphsRemoved, List<Integer> picturePages) {
        static final RedactionReport NONE = new RedactionReport(0, 0, List.of());
    }

    /** The exported PDF and how its redaction went. */
    public record Exported(byte[] pdf, RedactionReport redaction) {
    }

    public byte[] export(List<PageSpec> pages) throws IOException {
        return export(pages, false);
    }

    /**
     * @param flatten replace every page by a picture of it: the text that edits covered is then gone
     *                for good, instead of sitting unseen under the cover-up
     */
    public byte[] export(List<PageSpec> pages, boolean flatten) throws IOException {
        return exportWithReport(pages, flatten).pdf();
    }

    public Exported exportWithReport(List<PageSpec> pages, boolean flatten) throws IOException {
        Map<String, PDDocument> sources = new HashMap<>();
        TextEditService.FontCache fonts = textEditService.newFontCache();
        int areas = 0;
        int removed = 0;
        List<Integer> picturePages = new ArrayList<>();
        try (PDDocument output = new PDDocument()) {
            for (PageSpec spec : pages) {
                PDDocument source = sources.computeIfAbsent(spec.documentId(), this::loadSource);
                PDPage sourcePage = source.getPage(spec.pageIndex());
                PDPage imported = output.importPage(sourcePage);

                PageEdits edits = store.pageEditsSnapshot(spec.documentId(), spec.pageIndex());
                if (!edits.isEmpty()) {
                    textEditService.applyEdits(source, store.getContent(spec.documentId()), spec.pageIndex(), output,
                            imported, edits, fonts, spec.documentId());
                }
                // After the edits, so that what an edit put inside an area is taken out as well.
                List<Redaction> redactions = store.redactions(spec.documentId(), spec.pageIndex());
                if (!redactions.isEmpty()) {
                    Redactor.Result result = Redactor.apply(output, imported, redactions);
                    areas += result.areas();
                    removed += result.glyphsRemoved();
                    if (result.rasterized()) {
                        picturePages.add(output.getNumberOfPages());
                    }
                }
                // Text recognized on a scan goes in as invisible text, so the export can be searched and copied;
                // a picture-only export has no text anyway. Lines inside redaction areas are left out, and so are
                // lines rewritten as text boxes: their scanned letters are covered, the box holds the text now.
                List<OcrText> recognized = OcrText.notRewritten(store.ocr(spec.documentId(), spec.pageIndex()),
                        edits.added());
                if (!recognized.isEmpty() && !flatten) {
                    PDFont font = textEditService.defaultFont(fonts, output);
                    if (font != null) {
                        OcrTextLayer.add(output, imported, recognized, font, redactions);
                    }
                }
                if (spec.rotation() != 0) {
                    imported.setRotation((sourcePage.getRotation() + spec.rotation()) % 360);
                }
            }

            RedactionReport report = areas == 0 ? RedactionReport.NONE
                    : new RedactionReport(areas, removed, List.copyOf(picturePages));
            if (flatten) {
                return new Exported(rasterize(output), report);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            output.save(out);
            return new Exported(out.toByteArray(), report);
        } finally {
            sources.values().forEach(PdfExportService::closeQuietly);
        }
    }

    /** A document of one picture per page, each as big as the page it shows. */
    private static byte[] rasterize(PDDocument document) throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        try (PDDocument flat = new PDDocument()) {
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, FLATTEN_DPI, ImageType.RGB);
                PDRectangle size = new PDRectangle(image.getWidth() * 72f / FLATTEN_DPI,
                        image.getHeight() * 72f / FLATTEN_DPI);
                PDPage page = new PDPage(size);
                flat.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(flat, page)) {
                    cs.drawImage(JPEGFactory.createFromImage(flat, image, 0.9f), 0, 0, size.getWidth(), size.getHeight());
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            flat.save(out);
            return out.toByteArray();
        }
    }

    private PDDocument loadSource(String documentId) {
        try {
            return Loader.loadPDF(store.getContent(documentId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void closeQuietly(PDDocument document) {
        try {
            document.close();
        } catch (IOException ignored) {
            // best-effort cleanup of temporary in-memory documents
        }
    }
}
