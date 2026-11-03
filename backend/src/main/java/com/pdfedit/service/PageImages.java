package com.pdfedit.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * The pages of an exported PDF as pictures (사진으로 내보내기): one picture for one page, else a ZIP of one
 * picture per page, numbered in order. Each page is drawn, written and let go before the next, so a long
 * document needs the memory of one page at a time.
 */
public final class PageImages {

    public enum Format {
        JPG("jpg", "image/jpeg"), PNG("png", "image/png");

        final String extension;
        final String contentType;

        Format(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        /** "png" (any case) for PNG; anything else, nothing included, is JPG - the smaller, the usual for photos. */
        public static Format of(String name) {
            return name != null && name.strip().equalsIgnoreCase("png") ? PNG : JPG;
        }
    }

    public static final float DEFAULT_DPI = 200f;
    public static final float MIN_DPI = 72f;
    public static final float MAX_DPI = 600f;
    private static final float JPEG_QUALITY = 0.92f;
    public static final String ZIP_TYPE = "application/zip";

    /** The pictures, or the ZIP holding them, and the name to save them as. */
    public record Images(byte[] content, String contentType, String fileName, int pages) {
    }

    private PageImages() {
    }

    /**
     * @param stem the name without an extension ("계약서_수정본"): the picture is "계약서_수정본.jpg", the
     *             ZIP "계약서_수정본.zip" holding "계약서_수정본_01.jpg", "계약서_수정본_02.jpg"...
     */
    public static Images render(byte[] pdf, Format format, float dpi, String stem) throws IOException {
        float wanted = Float.isFinite(dpi) ? Math.max(MIN_DPI, Math.min(MAX_DPI, dpi)) : DEFAULT_DPI;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int count = document.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(document);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (count == 1) {
                write(draw(renderer, document, 0, wanted), format, out);
                return new Images(out.toByteArray(), format.contentType, stem + "." + format.extension, 1);
            }
            int digits = Math.max(2, String.valueOf(count).length());
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (int i = 0; i < count; i++) {
                    BufferedImage image = draw(renderer, document, i, wanted);
                    String number = String.format(Locale.ROOT, "%0" + digits + "d", i + 1);
                    zip.putNextEntry(new ZipEntry(stem + "_" + number + "." + format.extension));
                    write(image, format, zip);
                    zip.closeEntry();
                }
            }
            return new Images(out.toByteArray(), ZIP_TYPE, stem + ".zip", count);
        }
    }

    /** The page as it shows (turned as the PDF turns it), at the DPI asked unless the page is very large. */
    private static BufferedImage draw(PDFRenderer renderer, PDDocument document, int index, float dpi)
            throws IOException {
        float capped = RenderLimits.cappedDpi(document.getPage(index).getCropBox(), dpi);
        return renderer.renderImageWithDPI(index, capped, ImageType.RGB);
    }

    private static void write(BufferedImage image, Format format, OutputStream out) throws IOException {
        if (format == Format.PNG) {
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("PNG를 쓸 수 없습니다.");
            }
            return;
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPG를 쓸 수 없습니다.");
        }
        ImageWriter writer = writers.next();
        // In memory, not a temporary file; closing it leaves the ZIP it writes into open.
        try (ImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
