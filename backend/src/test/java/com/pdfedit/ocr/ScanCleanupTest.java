package com.pdfedit.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** A faint or speckled scan is cleaned for reading; a clean one is read as it is. */
class ScanCleanupTest {

    private static BufferedImage text(Color paper, Color ink) {
        BufferedImage image = new BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(paper);
        g.fillRect(0, 0, 600, 300);
        g.setColor(ink);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
        for (int i = 0; i < 4; i++) {
            g.drawString("FAX 031-123-4567", 30, 70 + 60 * i);
        }
        g.dispose();
        return image;
    }

    private static int grey(int rgb) {
        return (((rgb >> 16) & 0xff) * 299 + ((rgb >> 8) & 0xff) * 587 + (rgb & 0xff) * 114) / 1000;
    }

    private static int darkest(BufferedImage image) {
        int min = 255;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                min = Math.min(min, grey(image.getRGB(x, y)));
            }
        }
        return min;
    }

    @Test
    void aCleanScanIsReadAsItIs() {
        BufferedImage clean = text(Color.WHITE, Color.BLACK);
        assertThat(ScanCleanup.forReading(clean)).isSameAs(clean);
    }

    @Test
    void faintInkOnTintedPaperIsMadeBlackOnWhite() {
        BufferedImage faint = text(new Color(215, 210, 200), new Color(150, 150, 150));
        BufferedImage cleaned = ScanCleanup.forReading(faint);
        assertThat(cleaned).isNotSameAs(faint);
        assertThat(darkest(cleaned)).isLessThan(20);
        assertThat(grey(cleaned.getRGB(590, 290))).isGreaterThan(245);
    }

    @Test
    void loneSpecksAreTakenOutAndLettersKept() {
        BufferedImage dusty = text(Color.WHITE, Color.BLACK);
        Random random = new Random(7);
        for (int i = 0; i < 4000; i++) {
            dusty.setRGB(random.nextInt(600), random.nextInt(300), 0);
        }
        BufferedImage cleaned = ScanCleanup.forReading(dusty);
        assertThat(cleaned).isNotSameAs(dusty);
        int[] grey = new int[600 * 300];
        int[] rgb = cleaned.getRGB(0, 0, 600, 300, null, 0, 600);
        for (int i = 0; i < rgb.length; i++) {
            grey[i] = grey(rgb[i]);
        }
        assertThat(ScanCleanup.speckled(grey, 600, 300)).isFalse();
        // The letters are still there: a good share of the picture's ink is left.
        long ink = java.util.Arrays.stream(grey).filter(v -> v < 128).count();
        assertThat(ink).isGreaterThan(5000);
    }
}
