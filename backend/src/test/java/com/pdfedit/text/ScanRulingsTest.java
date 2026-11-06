package com.pdfedit.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/** A scanned table's borders are found in the page's picture; letters are not taken for borders. */
class ScanRulingsTest {

    private static BufferedImage page() {
        BufferedImage image = new BufferedImage(600, 400, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 400);
        g.setColor(new Color(40, 40, 40));
        g.setStroke(new BasicStroke(2));
        // A 3 x 2 table: rows at 50, 100, 150; columns at 50, 250, 550.
        for (int y : new int[] {50, 100, 150}) {
            g.drawLine(50, y, 550, y);
        }
        for (int x : new int[] {50, 250, 550}) {
            g.drawLine(x, 50, x, 150);
        }
        // A border scanned askew: it drops 5 pixels over its length.
        g.drawLine(50, 300, 550, 305);
        // Letters in the cells.
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        g.drawString("차대번호 KMHAB81CBNU123456", 60, 80);
        g.dispose();
        return image;
    }

    @Test
    void findsTheTablesBordersAndFollowsALineScannedAskew() {
        RulingLines.Lines lines = ScanRulings.of(page(), 1f);

        assertThat(lines.horizontal()).extracting(h -> (int) Math.round(h.y())).containsExactlyInAnyOrder(50, 100, 150, 303);
        assertThat(lines.horizontal()).allSatisfy(h -> {
            assertThat(h.x1()).isCloseTo(48.5, within(1.5));
            assertThat(h.x2()).isCloseTo(552.5, within(1.5));
        });
        assertThat(lines.vertical()).extracting(v -> (int) Math.round(v.x())).containsExactlyInAnyOrder(50, 250, 550);
    }

    @Test
    void aPageOfOnlyTextHasNoBorders() {
        BufferedImage image = new BufferedImage(400, 200, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 200);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        for (int i = 0; i < 6; i++) {
            g.drawString("자동차등록증 一 二 三 Lorem ipsum 2025-04-21", 10, 30 + i * 28);
        }
        g.dispose();

        RulingLines.Lines lines = ScanRulings.of(image, 1f);

        assertThat(lines.horizontal()).isEmpty();
        assertThat(lines.vertical()).isEmpty();
    }
}
