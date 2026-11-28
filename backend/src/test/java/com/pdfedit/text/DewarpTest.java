package com.pdfedit.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/** A page photographed at a slant (its lines sloping more at the top than at the bottom) is laid flat. */
class DewarpTest {

    /** A page of 30 lines of text, set straight. */
    private static BufferedImage page() {
        BufferedImage image = new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        for (int r = 0; r < 30; r++) {
            g.drawString("견적서 품목 " + r + " 수량 12 단가 240,070 합계 2,880,840원", 90, 120 + r * 52);
        }
        g.dispose();
        return image;
    }

    /** The page with its lines sloping {@code top} degrees at the top down to {@code bottom} at the bottom. */
    private static BufferedImage slanted(BufferedImage flat, double top, double bottom) {
        int w = flat.getWidth();
        int h = flat.getHeight();
        Dewarp.Field field = new Dewarp.Field((w - 1) / 2.0, new double[][] {{0, top}, {h - 1, bottom}});
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            double t = Math.tan(Math.toRadians(field.degrees(y)));
            for (int x = 0; x < w; x++) {
                int sy = (int) Math.round(y - (x - field.cx()) * t);
                out.setRGB(x, y, sy >= 0 && sy < h ? flat.getRGB(x, sy) : 0xffffff);
            }
        }
        return out;
    }

    /** The slopes of the page's top, middle and bottom thirds. */
    private static double[] slopes(BufferedImage image) {
        int[] grey = Dewarp.grey(image);
        int w = image.getWidth();
        int h = image.getHeight();
        int step = Dewarp.step(w, h);
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = Dewarp.estimate(grey, w, i * h / 3, (i + 1) * h / 3, step).angle();
        }
        return out;
    }

    @Test
    void aPagePhotographedAtASlantIsLaidFlat() {
        BufferedImage photo = slanted(page(), 2.4, -0.4);
        double[] before = slopes(photo);
        assertThat(before[0] - before[2]).as("slopes differ top to bottom").isGreaterThan(1.5);

        BufferedImage flat = Dewarp.flattened(photo);

        assertThat(flat).isNotNull();
        double worstBefore = 0;
        for (double slope : before) {
            worstBefore = Math.max(worstBefore, Math.abs(slope));
        }
        for (double slope : slopes(flat)) {
            assertThat(Math.abs(slope)).isLessThan(Math.min(0.6, worstBefore / 3));
        }
    }

    @Test
    void aStraightPageAndOneTurnedEvenlyAreNotLaidFlat() {
        assertThat(Dewarp.flattened(page())).isNull();
        // Turned evenly (the same slope everywhere): turning it back is enough.
        assertThat(Dewarp.flattened(slanted(page(), 1.5, 1.5))).isNull();
    }

    /** A one-page PDF of the picture, A4. */
    private static byte[] pdfOf(BufferedImage picture) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            doc.addPage(page);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.drawImage(org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, picture), 0, 0,
                        page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void aScannedPageAtASlantIsMadeAFlatCopyAndAStraightOneIsNot() throws Exception {
        byte[] flat = PageStraightener.flattened(pdfOf(slanted(page(), 2.4, -0.4)), 0);
        assertThat(flat).isNotNull();
        try (var doc = org.apache.pdfbox.Loader.loadPDF(flat)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isCloseTo(org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getWidth(),
                    org.assertj.core.data.Offset.offset(0.5f));
            BufferedImage seen = new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(0, 150);
            for (double slope : slopes(seen)) {
                assertThat(Math.abs(slope)).isLessThan(0.7);
            }
        }
        assertThat(PageStraightener.flattened(pdfOf(page()), 0)).isNull();
    }
}
