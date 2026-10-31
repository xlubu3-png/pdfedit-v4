package com.pdfedit.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import com.pdfedit.text.AddedImage;

/** Pictures put on a page: checking what is uploaded and the boxes placed, and turning a picture into PDF content. */
public final class ImageDrawing {

    private static final long MAX_PIXELS = 40_000_000L;
    private static final String TOO_BIG = "그림이 너무 큽니다. 크기를 줄여서 올려 주세요.";
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
        // A small file can say it holds a picture of billions of pixels: the size is taken from its
        // header and refused there, since decoding it is what would fill the memory.
        if (headerPixels(data) > MAX_PIXELS) {
            throw new InvalidEditException(TOO_BIG);
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            throw new InvalidEditException("그림으로 읽을 수 없는 파일입니다. PNG, JPG, GIF, BMP 파일을 올려 주세요.");
        }
        if ((long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
            throw new InvalidEditException(TOO_BIG);
        }
        if (isPng(data)) {
            return new Upload(data, image.getWidth(), image.getHeight(), "image/png");
        }
        if (isJpeg(data)) {
            int orientation = exifOrientation(data);
            if (orientation == 1) {
                return new Upload(data, image.getWidth(), image.getHeight(), "image/jpeg");
            }
            // A phone saves its photo as the sensor saw it and notes how to turn it: readers of this
            // app (and the PDF) know nothing of the note, so the picture is turned for good here.
            BufferedImage upright = upright(image, orientation);
            return new Upload(jpeg(upright), upright.getWidth(), upright.getHeight(), "image/jpeg");
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return new Upload(png.toByteArray(), image.getWidth(), image.getHeight(), "image/png");
    }

    /** The pixels the first picture in the file says it has, without decoding it; 0 when no reader knows the file. */
    private static long headerPixels(byte[] data) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = stream == null ? null : ImageIO.getImageReaders(stream);
            if (readers == null || !readers.hasNext()) {
                return 0;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                return (long) reader.getWidth(0) * reader.getHeight(0);
            } catch (IOException | RuntimeException unreadable) {
                return 0; // left to the full read, which says the file is not a picture
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * The EXIF orientation (1 to 8) a JPEG carries, 1 (as it is) when it has none or cannot be read:
     * 6 means "turn 90 degrees clockwise to see it right side up", the usual portrait photo.
     */
    static int exifOrientation(byte[] d) {
        try {
            int i = 2;
            while (i + 4 <= d.length && (d[i] & 0xFF) == 0xFF) {
                int marker = d[i + 1] & 0xFF;
                if (marker == 0xD9 || marker == 0xDA) {
                    return 1; // the picture data starts: no more headers
                }
                int length = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
                boolean exif = marker == 0xE1 && length >= 16 && d[i + 4] == 'E' && d[i + 5] == 'x'
                        && d[i + 6] == 'i' && d[i + 7] == 'f' && d[i + 8] == 0 && d[i + 9] == 0;
                if (exif) {
                    int tiff = i + 10;
                    boolean little = d[tiff] == 'I';
                    int ifd = tiff + (int) number(d, tiff + 4, 4, little);
                    int entries = (int) number(d, ifd, 2, little);
                    for (int e = 0; e < entries; e++) {
                        int at = ifd + 2 + e * 12;
                        if (number(d, at, 2, little) == 0x0112) {
                            int value = (int) number(d, at + 8, 2, little);
                            return value >= 1 && value <= 8 ? value : 1;
                        }
                    }
                    return 1;
                }
                i += 2 + length;
            }
        } catch (RuntimeException unreadable) {
            // a damaged header is no reason to refuse the picture
        }
        return 1;
    }

    private static long number(byte[] d, int at, int bytes, boolean little) {
        long value = 0;
        for (int k = 0; k < bytes; k++) {
            int b = d[at + (little ? bytes - 1 - k : k)] & 0xFF;
            value = (value << 8) | b;
        }
        return value;
    }

    /** The picture turned and flipped as its EXIF orientation says, so that it is right side up. */
    static BufferedImage upright(BufferedImage source, int orientation) {
        int w = source.getWidth();
        int h = source.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = source.getRGB(x, y);
                switch (orientation) {
                    case 2 -> out.setRGB(w - 1 - x, y, rgb);
                    case 3 -> out.setRGB(w - 1 - x, h - 1 - y, rgb);
                    case 4 -> out.setRGB(x, h - 1 - y, rgb);
                    case 5 -> out.setRGB(y, x, rgb);
                    case 6 -> out.setRGB(h - 1 - y, x, rgb);
                    case 7 -> out.setRGB(h - 1 - y, w - 1 - x, rgb);
                    case 8 -> out.setRGB(y, w - 1 - x, rgb);
                    default -> out.setRGB(x, y, rgb);
                }
            }
        }
        return out;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.92f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
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
