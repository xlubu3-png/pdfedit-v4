package com.pdfedit.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A page scanned on its side or upside down is told, and how far to turn it; an upright one is left. */
class UprightTurnTest {

    private static OcrEngine engine;

    @BeforeAll
    static void load() throws Exception {
        engine = OcrEngine.load();
    }

    @AfterAll
    static void close() throws Exception {
        engine.close();
    }

    private static final String[] LINES = {
        "QUOTATION No. 2026-1104 AIR SHOWER UNIT",
        "Delivery within 2 weeks after the order",
        "Payment in cash before shipping, VAT included",
        "Contact 010-2345-6789 Fax 031-123-4567",
        "Valid for 30 days from the date of issue",
    };

    /** An upright page of lines (120 dpi, A4), as it would be seen turned clockwise by {@code quarter}. */
    private static BufferedImage page(int quarter) {
        BufferedImage upright = new BufferedImage(992, 1403, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = upright.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, upright.getWidth(), upright.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 26));
        for (int i = 0; i < LINES.length; i++) {
            g.drawString(LINES[i], 80, 200 + 70 * i);
        }
        g.dispose();
        if (quarter == 0) {
            return upright;
        }
        boolean side = quarter % 180 != 0;
        int w = side ? upright.getHeight() : upright.getWidth();
        int h = side ? upright.getWidth() : upright.getHeight();
        BufferedImage turned = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D t = turned.createGraphics();
        AffineTransform at = new AffineTransform();
        at.translate(w / 2.0, h / 2.0);
        at.rotate(Math.toRadians(quarter));
        at.translate(-upright.getWidth() / 2.0, -upright.getHeight() / 2.0);
        t.drawImage(upright, at, null);
        t.dispose();
        return turned;
    }

    @Test
    void anUprightPageStaysAsItIs() throws Exception {
        assertThat(engine.uprightTurn(page(0))).isZero();
    }

    @Test
    void aPageUpsideDownIsTurnedHalfway() throws Exception {
        assertThat(engine.uprightTurn(page(180))).isEqualTo(180);
    }

    @Test
    void aPageOnItsSideIsTurnedBackTheRightWay() throws Exception {
        // Seen turned 90 clockwise, it is set upright by turning it 270 more; seen turned 270, by 90.
        assertThat(engine.uprightTurn(page(90))).isEqualTo(270);
        assertThat(engine.uprightTurn(page(270))).isEqualTo(90);
    }

    @Test
    void aBlankPageIsLeftAlone() throws Exception {
        BufferedImage blank = new BufferedImage(992, 1403, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 992, 1403);
        g.dispose();
        assertThat(engine.uprightTurn(blank)).isZero();
    }
}
