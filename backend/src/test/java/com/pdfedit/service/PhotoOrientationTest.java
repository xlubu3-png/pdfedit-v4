package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/** A phone's photo is saved as the sensor saw it, with a note (EXIF) on how to turn it: the note is honoured. */
class PhotoOrientationTest {

    /** 20 x 10: red on the left half, blue on the right half. */
    private static BufferedImage twoHalves() {
        BufferedImage image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 20; x++) {
                image.setRGB(x, y, (x < 10 ? Color.RED : Color.BLUE).getRGB());
            }
        }
        return image;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    /** The JPEG with an EXIF segment (little endian) that says {@code orientation}, right after the start marker. */
    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = {
            'I', 'I', 0x2A, 0, 8, 0, 0, 0, // header: little endian, first directory at 8
            1, 0, // one entry
            0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0, // tag 0x0112, SHORT, one value
            0, 0, 0, 0, // no next directory
        };
        int length = 2 + 6 + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static boolean isRed(int rgb) {
        Color c = new Color(rgb);
        return c.getRed() > 180 && c.getBlue() < 90;
    }

    private static boolean isBlue(int rgb) {
        Color c = new Color(rgb);
        return c.getBlue() > 180 && c.getRed() < 90;
    }

    @Test
    void aPortraitPhotoIsTurnedUprightAndKeptAsAJpeg() throws IOException {
        byte[] photo = withOrientation(jpeg(twoHalves()), 6); // "turn 90 degrees clockwise"
        assertThat(ImageDrawing.exifOrientation(photo)).isEqualTo(6);

        ImageDrawing.Upload upload = ImageDrawing.read(photo);

        assertThat(upload.contentType()).isEqualTo("image/jpeg");
        assertThat(upload.width()).isEqualTo(10);
        assertThat(upload.height()).isEqualTo(20);
        BufferedImage turned = ImageIO.read(new ByteArrayInputStream(upload.data()));
        assertThat(turned.getWidth()).isEqualTo(10);
        // clockwise: what was the left half is now the top half
        assertThat(isRed(turned.getRGB(5, 3))).as("top is the old left").isTrue();
        assertThat(isBlue(turned.getRGB(5, 16))).as("bottom is the old right").isTrue();
    }

    @Test
    void everyOrientationPutsTheLeftHalfWhereTheNoteSays() {
        BufferedImage source = twoHalves();
        assertThat(isRed(ImageDrawing.upright(source, 2).getRGB(15, 5))).as("mirrored: red on the right").isTrue();
        assertThat(isRed(ImageDrawing.upright(source, 3).getRGB(15, 5))).as("upside down: red on the right").isTrue();
        assertThat(isRed(ImageDrawing.upright(source, 4).getRGB(5, 5))).as("flipped vertically: red stays left").isTrue();
        BufferedImage eight = ImageDrawing.upright(source, 8); // counter clockwise: the left half goes to the bottom
        assertThat(eight.getHeight()).isEqualTo(20);
        assertThat(isRed(eight.getRGB(5, 16))).isTrue();
        assertThat(isBlue(eight.getRGB(5, 3))).isTrue();
    }

    @Test
    void aPhotoWithoutANoteOrWithADamagedOneIsKeptAsItCame() throws IOException {
        byte[] plain = jpeg(twoHalves());
        assertThat(ImageDrawing.exifOrientation(plain)).isEqualTo(1);
        assertThat(ImageDrawing.read(plain).data()).isEqualTo(plain);

        byte[] damaged = withOrientation(plain, 6);
        byte[] cut = java.util.Arrays.copyOf(damaged, 30); // ends inside the note
        assertThat(ImageDrawing.exifOrientation(cut)).isEqualTo(1);
        assertThat(ImageDrawing.exifOrientation(new byte[] {1, 2, 3})).isEqualTo(1);
    }

    @Test
    void aValueOutsideOneToEightIsIgnored() throws IOException {
        assertThat(ImageDrawing.exifOrientation(withOrientation(jpeg(twoHalves()), 9))).isEqualTo(1);
    }
}
