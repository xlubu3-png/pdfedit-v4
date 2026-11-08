package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** A page with photographs on it is previewed as a JPEG; letters, lines and grey scans stay PNG. */
class PreviewFormatTest {

    private static BufferedImage page() {
        BufferedImage image = new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1240, 1754);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 24));
        for (int i = 0; i < 30; i++) {
            g.drawString("A line of text on the page, number " + i, 100, 150 + 40 * i);
        }
        g.setColor(new Color(200, 0, 0));
        g.fillRect(100, 1500, 300, 60); // a red logo
        g.dispose();
        return image;
    }

    @Test
    void aPageOfTextAndAColouredLogoIsNotPhotographs() {
        assertThat(TextEditService.photographic(page())).isFalse();
    }

    @Test
    void aPageWithAPhotographOnItIs() {
        BufferedImage image = page();
        Random random = new Random(3);
        for (int y = 300; y < 900; y++) {
            for (int x = 300; x < 1000; x++) {
                image.setRGB(x, y, (random.nextInt(256) << 16) | ((x / 3 & 0xff) << 8) | (y / 4 & 0xff));
            }
        }
        assertThat(TextEditService.photographic(image)).isTrue();
    }
}
