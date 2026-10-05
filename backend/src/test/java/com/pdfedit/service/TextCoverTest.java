package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Map;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class TextCoverTest {

    private static BufferedImage filled(int w, int h, Color colour) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return image;
    }

    private static void block(BufferedImage image, int x, int y, int w, int h, Color colour) {
        Graphics2D g = image.createGraphics();
        g.setColor(colour);
        g.fillRect(x, y, w, h);
        g.dispose();
    }

    @Test
    void theInkIsWhereTheTwoPicturesDiffer() {
        BufferedImage without = filled(60, 40, new Color(230, 230, 230));
        BufferedImage with = filled(60, 40, new Color(230, 230, 230));
        block(with, 10, 20, 5, 3, Color.BLACK);

        TextCover.Area ink = TextCover.inkBounds(with, without, new TextCover.Area(0, 0, 60, 40));

        assertThat(ink).isEqualTo(new TextCover.Area(10, 20, 15, 23));
    }

    @Test
    void identicalOrNearlyIdenticalPicturesHaveNoInk() {
        BufferedImage a = filled(30, 30, new Color(200, 200, 200));
        BufferedImage b = filled(30, 30, new Color(204, 204, 204));

        assertThat(TextCover.inkBounds(a, b, new TextCover.Area(0, 0, 30, 30))).isNull();
    }

    @Test
    void onlyTheGivenAreaIsLookedAt() {
        BufferedImage without = filled(60, 40, Color.WHITE);
        BufferedImage with = filled(60, 40, Color.WHITE);
        block(with, 50, 5, 4, 4, Color.BLACK); // outside the area: another line's ink

        assertThat(TextCover.inkBounds(with, without, new TextCover.Area(0, 0, 30, 40))).isNull();
    }

    @Test
    void growingAddsAMarginButStaysInsideTheLimit() {
        TextCover.Area limit = new TextCover.Area(5, 5, 50, 30);

        assertThat(TextCover.grow(new TextCover.Area(10, 10, 20, 20), 2, limit)).isEqualTo(new TextCover.Area(8, 8, 22, 22));
        assertThat(TextCover.grow(new TextCover.Area(5, 6, 49, 29), 3, limit)).isEqualTo(new TextCover.Area(5, 5, 50, 30));
    }

    @Test
    void theDominantColourAndItsShareTellAPlainFillFromAShadedOrRuledArea() {
        BufferedImage plain = filled(20, 10, new Color(230, 230, 230));
        BufferedImage ruled = filled(20, 10, new Color(230, 230, 230));
        block(ruled, 0, 0, 20, 3, Color.BLACK);

        Map.Entry<Integer, Double> flat = TextCover.dominantColour(plain, new TextCover.Area(0, 0, 20, 10));
        Map.Entry<Integer, Double> notFlat = TextCover.dominantColour(ruled, new TextCover.Area(0, 0, 20, 10));

        assertThat(flat.getKey()).isEqualTo(0xE6E6E6);
        assertThat(flat.getValue()).isEqualTo(1.0);
        assertThat(notFlat.getKey()).isEqualTo(0xE6E6E6);
        assertThat(notFlat.getValue()).isEqualTo(0.7);
    }

    @Test
    void userSpaceMapsToPixelsCountedFromTheTop() {
        PDRectangle crop = PDRectangle.A4;

        TextCover.Area area = TextCover.pixelArea(crop, 2f, 10f, 800f, 20f, 10f, 1190, 1684);

        assertThat(area.x0()).isEqualTo(20);
        assertThat(area.x1()).isEqualTo(60);
        assertThat(area.y0()).isEqualTo(63);
        assertThat(area.y1()).isEqualTo(84);
    }

    @Test
    void theAreaIsClampedToThePicture() {
        TextCover.Area area = TextCover.pixelArea(PDRectangle.A4, 2f, -50f, -50f, 5000f, 5000f, 1190, 1684);

        assertThat(area).isEqualTo(new TextCover.Area(0, 0, 1190, 1684));
    }
}
