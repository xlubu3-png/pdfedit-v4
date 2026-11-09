package com.pdfedit.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A cell's border at a line's edge is painted out before the line is read; a line on its own is left as it is. */
class EdgeTrimTest {

    private static OcrEngine engine;

    @BeforeAll
    static void load() throws Exception {
        engine = OcrEngine.load();
    }

    @AfterAll
    static void close() throws Exception {
        engine.close();
    }

    /** "60.5" in a cell, with the cell's border at {@code left} pixels and another at the right edge. */
    private static BufferedImage cell(String text, boolean borders, boolean doubled) {
        BufferedImage image = new BufferedImage(160, 44, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 160, 44);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        if (borders) {
            g.setStroke(new BasicStroke(2));
            g.drawLine(2, 0, 2, 44);
            if (doubled) {
                g.drawLine(6, 0, 6, 44);
            }
            g.drawLine(157, 0, 157, 44);
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 26));
        g.drawString(text, 22, 32);
        g.dispose();
        return image;
    }

    @Test
    void bordersAtTheEdgesArePaintedOut() {
        BufferedImage trimmed = EdgeTrim.trimmed(cell("60.5", true, false));
        assertThat(trimmed).isNotNull();
        // The borders are paper now; the letters are untouched.
        assertThat(trimmed.getRGB(2, 22) & 0xffffff).isGreaterThan(0xe0e0e0);
        assertThat(trimmed.getRGB(157, 22) & 0xffffff).isGreaterThan(0xe0e0e0);
        assertThat(EdgeTrim.trimmed(cell("60.5", true, true)).getRGB(6, 22) & 0xffffff).isGreaterThan(0xe0e0e0);
    }

    @Test
    void aLineWithNothingAtItsEdgesIsLeftAlone() {
        assertThat(EdgeTrim.trimmed(cell("60.5", false, false))).isNull();
    }

    @Test
    void aCellBetweenBordersIsReadWithoutThem() throws Exception {
        // As a page would give it: the box found around the letters takes the borders in.
        BufferedImage page = new BufferedImage(400, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 120);
        g.drawImage(cell("60.5", true, true), 100, 40, null);
        g.dispose();
        String read = engine.read(page).stream().map(OcrLine::text).reduce("", String::concat).replace(" ", "");
        assertThat(read).contains("60.5");
    }
}
