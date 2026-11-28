package com.pdfedit.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/** Lines packed with no blank row between (a close-packed table cell) are parted at their faintest rows. */
class ValleyRowsTest {

    /** A cell between two upright borders with {@code lines} lines of text set 30 pixels apart, letters 28 high. */
    private static BufferedImage cell(int lines) {
        BufferedImage image = new BufferedImage(260, lines * 30 + 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 2, image.getHeight());
        g.fillRect(image.getWidth() - 2, 0, 2, image.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        String[] words = {"위장관계 설사", "신경계 두통", "구역 구토 증상", "피부 발진"};
        for (int i = 0; i < lines; i++) {
            g.drawString(words[i], 12, 26 + i * 30);
        }
        g.dispose();
        return image;
    }

    @Test
    void threeLinesTouchingArePartedWhereTheyMeet() {
        BufferedImage three = cell(3);
        int[] cuts = OcrEngine.valleyRows(three, 3);
        assertThat(cuts).hasSize(2);
        assertThat(cuts[0]).isBetween(24, 36);
        assertThat(cuts[1]).isBetween(54, 66);
    }

    @Test
    void oneLineIsNotCut() {
        BufferedImage one = cell(1);
        BufferedImage tall = new BufferedImage(one.getWidth(), 68, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = tall.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, tall.getWidth(), tall.getHeight());
        // One line of big letters filling the height: no faint row in the middle.
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 64));
        g.drawString("위장관계", 10, 58);
        g.dispose();
        assertThat(OcrEngine.valleyRows(tall, 2)).isNull();
    }
}
