package com.pdfedit.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

/** A form's check boxes are found in a line's picture, and what was read off them is put right. */
class CheckBoxesTest {

    /** A line 48 pixels high: a box at x 10 - 40 (ticked or not), then a label; a round bullet after when asked. */
    private static BufferedImage line(boolean ticked, boolean ring) {
        BufferedImage image = new BufferedImage(400, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 48);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(60, 60, 60));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRect(10, 9, 30, 30);
        if (ticked) {
            g.setStroke(new BasicStroke(3f));
            g.drawLine(15, 24, 23, 33);
            g.drawLine(23, 33, 36, 13);
        }
        if (ring) {
            g.setStroke(new BasicStroke(1.5f));
            g.drawOval(300, 9, 30, 30);
        }
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 26));
        g.drawString("동의함", 60, 34);
        g.dispose();
        return image;
    }

    @Test
    void aBoxIsFoundTickedOrNotAndARoundBulletIsNot() {
        List<CheckBoxes.Box> empty = CheckBoxes.find(line(false, true));
        assertThat(empty).hasSize(1);
        assertThat(empty.getFirst().checked()).isFalse();
        assertThat(empty.getFirst().x0()).isBetween(8, 12);
        List<CheckBoxes.Box> ticked = CheckBoxes.find(line(true, false));
        assertThat(ticked).hasSize(1);
        assertThat(ticked.getFirst().checked()).isTrue();
    }

    @Test
    void lettersReadAtABoxBecomeTheBoxAndALeftOutBoxIsPutIn() {
        List<CheckBoxes.Box> ticked = List.of(new CheckBoxes.Box(10, 40, true));
        // "V 동의함": the V at the box's place (25/400).
        Object[] fixed = CheckBoxes.restore("V 동의함", new float[] {0.06f, 0.1f, 0.2f, 0.25f, 0.3f}, ticked, 400);
        assertThat(fixed).isNotNull();
        assertThat(fixed[0]).isEqualTo("☑ 동의함");
        assertThat((float[]) fixed[1]).hasSize(5);

        List<CheckBoxes.Box> empty = List.of(new CheckBoxes.Box(10, 40, false));
        Object[] put = CheckBoxes.restore("동의함", new float[] {0.2f, 0.25f, 0.3f}, empty, 400);
        assertThat(put[0]).isEqualTo("□ 동의함");
        assertThat((float[]) put[1]).hasSize(5);
    }

    @Test
    void aWordOrANumberAtABoxIsLeftAsRead() {
        List<CheckBoxes.Box> box = List.of(new CheckBoxes.Box(10, 40, true));
        // Hangul there, a "1" (a number), a Latin word going on ("VC"), nothing after it: as read.
        assertThat(CheckBoxes.restore("동 의함", new float[] {0.06f, 0.1f, 0.2f, 0.25f}, box, 400)).isNull();
        assertThat(CheckBoxes.restore("1 항목", new float[] {0.06f, 0.1f, 0.2f, 0.25f}, box, 400)).isNull();
        assertThat(CheckBoxes.restore("VC 운용사", new float[] {0.06f, 0.09f, 0.15f, 0.2f, 0.25f, 0.3f}, box, 400)).isNull();
        assertThat(CheckBoxes.restore("V", new float[] {0.06f}, box, 400)).isNull();
        // A bracket, and a letter of a unit after a slash, are what they say.
        assertThat(CheckBoxes.restore("[연료", new float[] {0.06f, 0.2f, 0.25f}, box, 400)).isNull();
        assertThat(CheckBoxes.restore("km/V,값", new float[] {0.01f, 0.02f, 0.04f, 0.06f, 0.2f, 0.25f}, box, 400)).isNull();
    }
}
