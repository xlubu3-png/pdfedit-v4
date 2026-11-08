package com.pdfedit.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

/** A scanned page fed askew is set straight by turning its drawing; a page of real text is left alone. */
class PageStraightenerTest {

    /** An A4 page whose picture is a form scanned {@code degrees} anticlockwise (as seen). */
    static byte[] scan(double degrees) throws Exception {
        BufferedImage picture = new BufferedImage(827, 1169, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 827, 1169);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setTransform(AffineTransform.getRotateInstance(Math.toRadians(-degrees), 413, 584));
        g.setColor(new Color(30, 30, 30));
        g.setStroke(new BasicStroke(2));
        for (int y = 200; y <= 700; y += 50) {
            g.drawLine(100, y, 730, y);
        }
        for (int x : new int[] {100, 300, 520, 730}) {
            g.drawLine(x, 200, x, 700);
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        for (int r = 0; r < 10; r++) {
            g.drawString("품목 " + r + "   AIR SHOWER   220,300", 110, 232 + r * 50);
        }
        g.dispose();
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(LosslessFactory.createFromImage(doc, picture), 0, 0, PDRectangle.A4.getWidth(),
                        PDRectangle.A4.getHeight());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static double angleOf(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return PageStraightener.angle(doc, 0);
        }
    }

    @Test
    void aPageScannedAskewIsTurnedBackStraight() throws Exception {
        byte[] askew = scan(2);
        double degrees = angleOf(askew);
        assertThat(degrees).isCloseTo(2, within(0.3));

        byte[] straight = PageStraightener.straightened(askew, 0, degrees);

        assertThat(angleOf(straight)).isZero();
        try (PDDocument doc = Loader.loadPDF(straight)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isEqualTo(PDRectangle.A4.getWidth());
        }
    }

    @Test
    void theOtherWayToo() throws Exception {
        byte[] askew = scan(-1.5);
        double degrees = angleOf(askew);
        assertThat(degrees).isCloseTo(-1.5, within(0.3));
        assertThat(angleOf(PageStraightener.straightened(askew, 0, degrees))).isZero();
    }

    @Test
    void aStraightScanOrAPageOfTextIsLeftAlone() throws Exception {
        assertThat(angleOf(scan(0))).isZero();
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.setTextMatrix(org.apache.pdfbox.util.Matrix.getRotateInstance(Math.toRadians(3), 72, 700));
                cs.showText("A page of real text, written at a slant on purpose, is not a scan to straighten.");
                cs.endText();
            }
            doc.save(out);
            assertThat(angleOf(out.toByteArray())).isZero();
        }
    }
}
