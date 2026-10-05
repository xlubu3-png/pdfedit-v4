package com.pdfedit.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import com.pdfedit.dto.PageSpec;

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

    public byte[] export(List<PageSpec> pages) throws IOException {
        return export(pages, false);
    }

    /**
     * @param flatten replace every page by a picture of it: the text that edits covered is then gone
     *                for good, instead of sitting unseen under the cover-up
     */
    public byte[] export(List<PageSpec> pages, boolean flatten) throws IOException {
        Map<String, PDDocument> sources = new HashMap<>();
        TextEditService.FontCache fonts = textEditService.newFontCache();
        try (PDDocument output = new PDDocument()) {
            for (PageSpec spec : pages) {
                PDDocument source = sources.computeIfAbsent(spec.documentId(), this::loadSource);
                PDPage sourcePage = source.getPage(spec.pageIndex());
                PDPage imported = output.importPage(sourcePage);

                PageEdits edits = store.pageEditsSnapshot(spec.documentId(), spec.pageIndex());
                if (!edits.isEmpty()) {
                    textEditService.applyEdits(source, spec.pageIndex(), output, imported, edits, fonts,
                            spec.documentId());
                }
                if (spec.rotation() != 0) {
                    imported.setRotation((sourcePage.getRotation() + spec.rotation()) % 360);
                }
            }

            if (flatten) {
                return rasterize(output);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            output.save(out);
            return out.toByteArray();
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
