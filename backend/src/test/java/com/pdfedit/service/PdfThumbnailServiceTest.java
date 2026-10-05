package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class PdfThumbnailServiceTest {

    private final PdfThumbnailService thumbnailService = new PdfThumbnailService();

    @Test
    void rendersPageAsPngScaledToRequestedWidth() throws IOException {
        byte[] pdf = singlePagePdf();

        byte[] png = thumbnailService.renderPage(pdf, 0, 240);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(240);
    }

    @Test
    void rejectsOutOfRangePageIndex() throws IOException {
        byte[] pdf = singlePagePdf();

        org.junit.jupiter.api.Assertions.assertThrows(
                IndexOutOfBoundsException.class,
                () -> thumbnailService.renderPage(pdf, 5, 240));
    }

    private static byte[] singlePagePdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
