package com.pdfedit.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

@Service
public class PdfThumbnailService {

    /** JPEG quality of the page list's pictures: no blur or blotches to see at their size. */
    static final float THUMBNAIL_QUALITY = 0.85f;

    /** A picture of the page, as PNG (exact: for comparing pages). */
    public byte[] renderPage(byte[] pdfBytes, int pageIndex, int width) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            BufferedImage image = render(document, pageIndex, width);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }

    /**
     * The page list's picture of the page, as JPEG: a scan's picture is a fifth of its PNG (about 9 KB instead of
     * 45), so a long document's list loads faster and the browser keeps less of it; made faster too.
     */
    public byte[] renderThumbnail(byte[] pdfBytes, int pageIndex, int width) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return jpeg(render(document, pageIndex, width));
        }
    }

    /**
     * The same, read from the document's file: only what the page needs is read, not the whole document into memory
     * (a 200 MB document's first pictures took over 1 GB).
     */
    public byte[] renderThumbnail(java.nio.file.Path pdfFile, int pageIndex, int width) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfFile.toFile())) {
            return jpeg(render(document, pageIndex, width));
        }
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (javax.imageio.stream.ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(THUMBNAIL_QUALITY);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static BufferedImage render(PDDocument document, int pageIndex, int width) throws IOException {
        if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
            throw new IndexOutOfBoundsException("Invalid page index: " + pageIndex);
        }
        PDRectangle box = document.getPage(pageIndex).getCropBox();
        float scale = RenderLimits.cappedScale(box, width / box.getWidth());
        PDFRenderer renderer = new PDFRenderer(document);
        // A scan's picture read only as finely as the small picture needs, not decoded whole and then shrunk: a
        // 300 dpi page took some 35 MB for a 240-pixel picture.
        renderer.setSubsamplingAllowed(true);
        // RGB on white: what JPEG keeps (a page has no see-through parts).
        return renderer.renderImage(pageIndex, scale, org.apache.pdfbox.rendering.ImageType.RGB);
    }
}
