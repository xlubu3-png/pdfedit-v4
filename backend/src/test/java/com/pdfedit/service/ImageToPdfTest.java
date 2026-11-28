package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

/** Picture files are opened as PDFs: a page each, as big as they were scanned, a phone photo the right way up. */
class ImageToPdfTest {

    /** A picture w x h, white, with a black block in its top-left corner (to see which way up it is). */
    private static BufferedImage marked(int w, int h, int type) {
        BufferedImage image = new BufferedImage(w, h, type);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w / 4, h / 4);
        g.dispose();
        return image;
    }

    private static byte[] write(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    /** A TIFF of several pages, each `dpi` dots per inch. */
    private static byte[] tiff(int dpi, BufferedImage... pages) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType("CCITT T.6");
            for (int i = 0; i < pages.length; i++) {
                IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(pages[i]), param);
                IIOMetadataNode root = new IIOMetadataNode("javax_imageio_1.0");
                IIOMetadataNode dimension = new IIOMetadataNode("Dimension");
                for (String name : new String[] {"HorizontalPixelSize", "VerticalPixelSize"}) {
                    IIOMetadataNode size = new IIOMetadataNode(name);
                    size.setAttribute("value", Float.toString(25.4f / dpi));
                    dimension.appendChild(size);
                }
                root.appendChild(dimension);
                meta.mergeTree("javax_imageio_1.0", root);
                if (i == 0) {
                    writer.write(null, new IIOImage(pages[i], null, meta), param);
                } else {
                    writer.writeInsert(-1, new IIOImage(pages[i], null, meta), param);
                }
            }
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    @Test
    void aFaxOfSeveralPagesIsAPageEachAsBigAsItWasScanned() throws IOException {
        // A4 at 200 dpi, black and white, three pages.
        BufferedImage page = marked(1654, 2339, BufferedImage.TYPE_BYTE_BINARY);
        byte[] pdf = ImageToPdf.toPdf(tiff(200, page, page, page), "팩스.tif");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(3);
            PDRectangle size = doc.getPage(0).getMediaBox();
            assertThat(size.getWidth()).isCloseTo(PDRectangle.A4.getWidth(), within(2f));
            assertThat(size.getHeight()).isCloseTo(PDRectangle.A4.getHeight(), within(2f));
        }
        assertThat(pdf.length).as("a fax stays small").isLessThan(200_000);
    }

    @Test
    void aPictureThatSaysNoSizeIsFittedOnAnA4PageItsOwnWay() throws IOException {
        byte[] pdf = ImageToPdf.toPdf(write(marked(1200, 800, BufferedImage.TYPE_INT_RGB), "png"), "화면.png");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDRectangle size = doc.getPage(0).getMediaBox();
            assertThat(size.getWidth()).isCloseTo(PDRectangle.A4.getHeight(), within(1f));
            assertThat(size.getHeight()).isCloseTo(PDRectangle.A4.getHeight() * 800 / 1200, within(1f));
        }
    }

    @Test
    void aJpegIsPutInAsItIs() throws IOException {
        byte[] jpeg = write(marked(800, 1000, BufferedImage.TYPE_INT_RGB), "jpg");
        byte[] pdf = ImageToPdf.toPdf(jpeg, "사진.JPG");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
        assertThat(pdf.length).isLessThan(jpeg.length + 4_000);
    }

    @Test
    void aPhoneTurnsAPictureRoundAndThePageShowsItTheRightWayUp() {
        BufferedImage stored = marked(400, 200, BufferedImage.TYPE_INT_RGB);
        // 6: to be turned a quarter clockwise - the marked corner goes to the top right.
        BufferedImage shown = ImageToPdf.upright(stored, 6);
        assertThat(shown.getWidth()).isEqualTo(200);
        assertThat(shown.getHeight()).isEqualTo(400);
        assertThat(shown.getRGB(195, 5) & 0xffffff).isEqualTo(0);
        assertThat(shown.getRGB(5, 5) & 0xffffff).isEqualTo(0xffffff);
        // 3: upside down - the mark goes to the bottom right; 8: a quarter the other way - to the bottom left.
        assertThat(ImageToPdf.upright(stored, 3).getRGB(395, 195) & 0xffffff).isEqualTo(0);
        assertThat(ImageToPdf.upright(stored, 8).getRGB(5, 395) & 0xffffff).isEqualTo(0);
    }

    @Test
    void theExifOrientationOfAJpegIsRead() throws IOException {
        byte[] jpeg = write(marked(64, 32, BufferedImage.TYPE_INT_RGB), "jpg");
        assertThat(ImageToPdf.ExifOrientation.of(jpeg)).isEqualTo(1);
        // An APP1 Exif block, big-endian, one entry: orientation 6.
        byte[] tiff = {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0};
        byte[] app1 = new byte[4 + 6 + tiff.length];
        app1[0] = (byte) 0xff;
        app1[1] = (byte) 0xe1;
        int length = 2 + 6 + tiff.length;
        app1[2] = (byte) (length >> 8);
        app1[3] = (byte) length;
        System.arraycopy(new byte[] {'E', 'x', 'i', 'f', 0, 0}, 0, app1, 4, 6);
        System.arraycopy(tiff, 0, app1, 10, tiff.length);
        // After the JFIF block (as cameras that write both put it).
        int afterJfif = 4 + (((jpeg[4] & 0xff) << 8) | (jpeg[5] & 0xff));
        byte[] withExif = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, withExif, 0, afterJfif);
        System.arraycopy(app1, 0, withExif, afterJfif, app1.length);
        System.arraycopy(jpeg, afterJfif, withExif, afterJfif + app1.length, jpeg.length - afterJfif);
        assertThat(ImageToPdf.ExifOrientation.of(withExif)).isEqualTo(6);

        // Opened, the page stands as the phone held it (taller than wide).
        byte[] pdf = ImageToPdf.toPdf(withExif, "사진.jpg");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDRectangle size = doc.getPage(0).getMediaBox();
            assertThat(size.getHeight()).isGreaterThan(size.getWidth());
            BufferedImage page = new PDFRenderer(doc).renderImage(0, 0.5f);
            assertThat(page.getRGB(page.getWidth() - 3, 3) & 0xff).isLessThan(80);
        }
    }

    @Test
    void namesAndBadFiles() {
        assertThat(ImageToPdf.isImage("스캔.TIFF")).isTrue();
        assertThat(ImageToPdf.isImage("a.jpeg")).isTrue();
        assertThat(ImageToPdf.isImage("a.pdf")).isFalse();
        assertThat(ImageToPdf.pdfName("팩스 2026.tif")).isEqualTo("팩스 2026.pdf");
        assertThatThrownBy(() -> ImageToPdf.toPdf(new byte[] {1, 2, 3}, "깨진.png"))
                .isInstanceOf(InvalidEditException.class).hasMessageContaining("깨진.png");
    }
}
