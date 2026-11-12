package com.pdfedit.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Unsure tick marks of a table are brought back by their likes, and a box that took in two lines is cut in two. */
class MarksAndTwoLinesTest {

    private static float[] box(float x, float y, float w, float h) {
        return new float[] {x, y, x + w, y, x + w, y + h, x, y + h};
    }

    @Test
    void anUnsureMarkIsKeptWhenTheSameMarkStandsSurelyInItsColumnOrRow() {
        List<float[]> boxes = List.of(box(100, 50, 25, 25), box(100, 90, 25, 25), box(300, 90, 26, 24), box(100, 130, 25, 25));
        String[] texts = {"○", "○", "○", "×"};
        // The second "○" was read at .44; the first (its column) and the third (its row) surely.
        assertThat(OcrEngine.rescuedMarks(boxes, texts, new float[] {0.92f, 0.44f, 0.9f, 0.4f}))
                .containsExactly(false, true, false, false); // no sure "×" anywhere: the last stays out
    }

    @Test
    void aMarkIsNotBroughtBackWithoutItsLikeOrWhereSomethingElseIsRead() {
        // Too unsure, another size, another column and row, or a word: none is kept.
        assertThat(OcrEngine.rescuedMarks(List.of(box(100, 50, 25, 25), box(100, 90, 25, 25)), new String[] {"○", "○"}, new float[] {0.9f, 0.2f}))
                .containsExactly(false, false);
        assertThat(OcrEngine.rescuedMarks(List.of(box(100, 50, 50, 50), box(100, 90, 25, 25)), new String[] {"○", "○"}, new float[] {0.9f, 0.44f}))
                .containsExactly(false, false);
        assertThat(OcrEngine.rescuedMarks(List.of(box(400, 50, 25, 25), box(100, 90, 25, 25)), new String[] {"○", "○"}, new float[] {0.9f, 0.44f}))
                .containsExactly(false, false);
        assertThat(OcrEngine.rescuedMarks(List.of(box(100, 50, 25, 25), box(100, 90, 25, 25)), new String[] {"합계", "합계"}, new float[] {0.9f, 0.44f}))
                .containsExactly(false, false);
        // Its like is there, but a kept line already lies over the place.
        assertThat(OcrEngine.rescuedMarks(List.of(box(100, 50, 25, 25), box(100, 90, 25, 25), box(60, 85, 200, 35)),
                new String[] {"○", "○", "해당 없음"}, new float[] {0.9f, 0.44f, 0.95f})).containsExactly(false, false, false);
    }

    private static BufferedImage lines(int height, int[][] bands) {
        BufferedImage image = new BufferedImage(200, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, height);
        g.setColor(Color.BLACK);
        for (int[] band : bands) {
            for (int x = 10; x < 190; x += 14) {
                g.fillRect(x, band[0], 8, band[1] - band[0]);
            }
        }
        g.dispose();
        return image;
    }

    @Test
    void aPictureOfTwoLinesIsCutBetweenThemAndAPictureOfOneIsNot() {
        int cut = OcrEngine.valleyRow(lines(48, new int[][] {{3, 21}, {27, 45}}));
        assertThat(cut).isBetween(21, 26);
        assertThat(OcrEngine.valleyRow(lines(48, new int[][] {{4, 44}}))).isZero();
        assertThat(OcrEngine.valleyRow(lines(12, new int[][] {{1, 5}, {7, 11}}))).as("too small to tell").isZero();
    }

    @Test
    void aBoxIsCutIntoItsUpperAndLowerPart() {
        float[][] parts = OcrEngine.cutBox(box(10, 20, 100, 40), 0.5f);
        assertThat(parts[0]).containsExactly(10, 20, 110, 20, 110, 40, 10, 40);
        assertThat(parts[1]).containsExactly(10, 40, 110, 40, 110, 60, 10, 60);
    }
}
