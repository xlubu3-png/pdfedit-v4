package com.pdfedit.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.pdfedit.service.TextEditService;
import com.pdfedit.text.FontMatcher;

/** The text reader is put down when unused for a while (its memory given back) and loaded again when needed. */
class EngineReleaseTest {

    private static final PageOcr OCR = new PageOcr(new TextEditService(new FontMatcher()));

    @AfterAll
    static void close() throws Exception {
        OCR.destroy();
    }

    /** A 200 dpi scan of one line. */
    private static byte[] scan(String text) throws Exception {
        BufferedImage scan = new BufferedImage(1654, 2339, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scan.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1654, 2339);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 40));
        g.drawString(text, 300, 400);
        g.dispose();
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(LosslessFactory.createFromImage(doc, scan), 0, 0, PDRectangle.A4.getWidth(),
                        PDRectangle.A4.getHeight());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String read(byte[] pdf) throws Exception {
        return String.join(" ", OCR.recognize(pdf, 0).lines().stream().map(line -> line.text()).toList());
    }

    @Test
    void anUnusedEngineIsPutDownAndLoadedAgainForTheNextPage() throws Exception {
        byte[] pdf = scan("Invoice 2026 total 240070");
        assertThat(OCR.loaded()).isFalse();
        String first = read(pdf);
        assertThat(first).contains("240070");
        assertThat(OCR.loaded()).isTrue();

        // Just used: kept.
        assertThat(OCR.releaseIfIdle(PageOcr.IDLE_RELEASE_MS)).isFalse();
        assertThat(OCR.loaded()).isTrue();

        // Unused long enough (here: at once): put down.
        assertThat(OCR.releaseIfIdle(0)).isTrue();
        assertThat(OCR.loaded()).isFalse();
        assertThat(OCR.releaseIfIdle(0)).isFalse();

        // The next page loads it again and reads the same.
        assertThat(read(pdf)).isEqualTo(first);
        assertThat(OCR.loaded()).isTrue();
    }
}
