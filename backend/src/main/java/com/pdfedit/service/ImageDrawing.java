package com.pdfedit.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import com.pdfedit.text.AddedImage;

/** Pictures put on a page: checking what is uploaded and the boxes placed, and turning a picture into PDF content. */
public final class ImageDrawing {

    private static final long MAX_PIXELS = 40_000_000L;
    private static final float MAX_OFFSET = 10_000f;
    private static final float MIN_SIDE = 1f;
    private static final float MAX_SIDE = 10_000f;
    static final int MAX_BOXES = 100;

    /** Whiter than this (lowest colour channel) is made see-through; fully kept below {@link #KEEP_BELOW}. */
    private static final int CLEAR_FROM = 245;
    private static final int KEEP_BELOW = 200;

    private ImageDrawing() {
    }

    /** An uploaded picture as it is kept: JPEG and PNG as they came, anything else re-encoded as PNG. */
    public record Upload(byte[] data, int width, int height, String contentType) {
    }

    /**
     * Reads an uploaded file as a picture.
     *
     * @throws InvalidEditException when it is not a picture this app can read, or is too big
     */
    public static Upload read(byte[] data) throws IOException {
        if (data.length == 0) {
            throw new InvalidEditException("빈 파일입니다.");
        }
        if (data.length > PdfDocumentStore.MAX_IMAGE_BYTES) {
            throw new InvalidEditException(
                    "그림 파일은 " + PdfDocumentStore.MAX_IMAGE_BYTES / (1024 * 1024) + "MB까지 올릴 수 있습니다.");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            throw new InvalidEditException("그림으로 읽을 수 없는 파일입니다. PNG, JPG, GIF, BMP 파일을 올려 주세요.");
        }
        if ((long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
            throw new InvalidEditException("그림이 너무 큽니다. 크기를 줄여서 올려 주세요.");
        }
        if (isPng(data)) {
            return new Upload(data, image.getWidth(), image.getHeight(), "image/png");
        }
        if (isJpeg(data)) {
            return new Upload(data, image.getWidth(), image.getHeight(), "image/jpeg");
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return new Upload(png.toByteArray(), image.getWidth(), image.getHeight(), "image/png");
    }

    public static String contentType(byte[] data) {
        return isJpeg(data) ? "image/jpeg" : "image/png";
    }

    private static boolean isPng(byte[] d) {
        return d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
    }

    private static boolean isJpeg(byte[] d) {
        return d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8;
    }

    /** Refuses boxes that are not sensible; the sizes and places are the browser's to choose, within limits. */
    static void checkBoxes(List<AddedImage> boxes) {
        if (boxes.size() > MAX_BOXES) {
            throw new InvalidEditException("한 페이지에는 그림을 " + MAX_BOXES + "개까지 넣을 수 있습니다.");
        }
        for (AddedImage box : boxes) {
            if (!(box.width() >= MIN_SIDE && box.width() <= MAX_SIDE && box.height() >= MIN_SIDE
                    && box.height() <= MAX_SIDE)) {
                throw new InvalidEditException("그림 크기가 올바르지 않습니다.");
            }
            if (!(Math.abs(box.x()) <= MAX_OFFSET && Math.abs(box.y()) <= MAX_OFFSET)) {
                throw new InvalidEditException("그림 위치가 페이지 밖입니다.");
            }
        }
    }

    /** The picture as PDF content of {@code doc}, with the white made see-through when asked. */
    static PDImageXObject create(PDDocument doc, byte[] data, boolean keyOutWhite) throws IOException {
        if (!keyOutWhite) {
            return PDImageXObject.createFromByteArray(doc, data, "picture");
        }
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(data));
        BufferedImage keyed = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                keyed.setRGB(x, y, withoutWhite(source.getRGB(x, y)));
            }
        }
        return LosslessFactory.createFromImage(doc, keyed);
    }

    /** One pixel with its alpha lowered the whiter it is: paper becomes clear, ink stays. */
    static int withoutWhite(int argb) {
        int alpha = argb >>> 24;
        int lowest = Math.min(argb >> 16 & 0xFF, Math.min(argb >> 8 & 0xFF, argb & 0xFF));
        if (lowest >= CLEAR_FROM) {
            alpha = 0;
        } else if (lowest > KEEP_BELOW) {
            alpha = alpha * (CLEAR_FROM - lowest) / (CLEAR_FROM - KEEP_BELOW);
        }
        return (alpha << 24) | (argb & 0xFFFFFF);
    }
}
