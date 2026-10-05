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

    public byte[] renderPage(byte[] pdfBytes, int pageIndex, int width) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
                throw new IndexOutOfBoundsException("Invalid page index: " + pageIndex);
            }
            PDRectangle box = document.getPage(pageIndex).getCropBox();
            float scale = width / box.getWidth();

            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImage(pageIndex, scale);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }
}
