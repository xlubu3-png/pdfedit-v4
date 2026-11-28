package com.pdfedit.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.w3c.dom.NodeList;

/**
 * A picture file - a phone photo (JPEG), a screenshot (PNG), a fax or scanner's TIFF of several pages, a BMP or a
 * GIF - opened as a PDF of one page per picture, so it is put in order, recognized, redacted and saved like a scan
 * (kordoc reads picture files too).
 * <p>
 * A page is as big as the picture says it was scanned (its dots per inch); a picture that says nothing, or that
 * would make a page too small or over a metre (a phone photo says 72 dpi), is fitted on an A4 page, turned to its
 * own way. A phone photo's turn (EXIF) is honoured. A JPEG is put in as it is, unless it has to be turned; other
 * pictures without loss (a fax's black and white stays one bit a dot).
 */
public final class ImageToPdf {

    private static final float POINTS_PER_INCH = 72;
    private static final float MM_PER_INCH = 25.4f;
    /** A page from the picture's own size is at least this big and at most this big (mm, its longer side). */
    private static final float MIN_MM = 50;
    private static final float MAX_MM = 600;
    /** Pages of a TIFF read at most (a fax is a few). */
    private static final int MAX_PAGES = 500;
    /** JPEG quality of a photo that had to be turned, or a colour picture of another kind too big for no loss. */
    private static final float JPEG_QUALITY = 0.9f;

    private ImageToPdf() {
    }

    /** Whether the file is a picture opened this way (by its name). */
    public static boolean isImage(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|gif|bmp|tiff?)$");
    }

    /** The PDF's name for a picture: "사진.jpg" -> "사진.pdf". */
    public static String pdfName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0 ? fileName.substring(0, dot) : fileName) + ".pdf";
    }

    /** The picture file as a PDF, a page for each of its pictures. */
    public static byte[] toPdf(byte[] file, String fileName) throws IOException {
        boolean jpeg = file.length > 3 && (file[0] & 0xff) == 0xff && (file[1] & 0xff) == 0xd8;
        int orientation = jpeg ? ExifOrientation.of(file) : 1;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(file));
                PDDocument doc = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new InvalidEditException("그림 파일을 읽을 수 없습니다: " + fileName);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, false, false);
                boolean manyPages = reader.getFormatName().toLowerCase(Locale.ROOT).startsWith("tif");
                int pages = manyPages ? Math.min(MAX_PAGES, reader.getNumImages(true)) : 1;
                for (int i = 0; i < pages; i++) {
                    PDImageXObject picture;
                    float dpi;
                    if (jpeg && orientation == 1) {
                        // Put in as it is, not decoded (Java cannot decode a CMYK one; the PDF can show it).
                        dpi = dotsPerInch(metadataOf(reader, i));
                        picture = JPEGFactory.createFromByteArray(doc, file);
                    } else {
                        IIOImage read;
                        try {
                            read = reader.readAll(i, null);
                        } catch (IOException | RuntimeException e) {
                            if (!jpeg) {
                                throw e;
                            }
                            // A photo Java cannot decode (CMYK, an odd header): put in as it is, not turned.
                            addPage(doc, JPEGFactory.createFromByteArray(doc, file), 0);
                            break;
                        }
                        BufferedImage image = (BufferedImage) read.getRenderedImage();
                        dpi = dotsPerInch(read.getMetadata());
                        image = upright(image, orientation);
                        boolean big = (long) image.getWidth() * image.getHeight() > 4_000_000L;
                        boolean colourPhoto = image.getColorModel().getPixelSize() >= 24;
                        picture = (jpeg || (big && colourPhoto))
                                ? JPEGFactory.createFromImage(doc, rgb(image), JPEG_QUALITY)
                                : LosslessFactory.createFromImage(doc, image);
                    }
                    addPage(doc, picture, dpi);
                }
            } finally {
                reader.dispose();
            }
            if (doc.getNumberOfPages() == 0) {
                throw new InvalidEditException("그림 파일에 그림이 없습니다: " + fileName);
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            if (e instanceof InvalidEditException invalid) {
                throw invalid;
            }
            throw new InvalidEditException("그림 파일을 읽을 수 없습니다: " + fileName);
        }
    }

    private static IIOMetadata metadataOf(ImageReader reader, int index) {
        try {
            return reader.getImageMetadata(index);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A page for the picture: as big as its dots per inch say, or fitted on A4. */
    private static void addPage(PDDocument doc, PDImageXObject picture, float dpi) throws IOException {
        int w = picture.getWidth();
        int h = picture.getHeight();
        float pageW;
        float pageH;
        float longerMm = dpi > 0 ? Math.max(w, h) / dpi * MM_PER_INCH : 0;
        if (longerMm >= MIN_MM && longerMm <= MAX_MM) {
            pageW = w / dpi * POINTS_PER_INCH;
            pageH = h / dpi * POINTS_PER_INCH;
        } else {
            PDRectangle a4 = w > h ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()) : PDRectangle.A4;
            float k = Math.min(a4.getWidth() / w, a4.getHeight() / h);
            pageW = w * k;
            pageH = h * k;
        }
        PDPage page = new PDPage(new PDRectangle(pageW, pageH));
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(picture, 0, 0, pageW, pageH);
        }
    }

    /** The picture's dots per inch across, from its metadata; 0 when it says none. */
    static float dotsPerInch(IIOMetadata metadata) {
        if (metadata == null || !metadata.isStandardMetadataFormatSupported()) {
            return 0;
        }
        try {
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_1.0");
            NodeList sizes = root.getElementsByTagName("HorizontalPixelSize");
            if (sizes.getLength() == 0) {
                return 0;
            }
            float mmPerDot = Float.parseFloat(((IIOMetadataNode) sizes.item(0)).getAttribute("value"));
            return mmPerDot > 0 ? MM_PER_INCH / mmPerDot : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The picture turned and mirrored as its EXIF orientation (1 - 8) says, to be seen the right way up. */
    static BufferedImage upright(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean sideways = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
        }
        BufferedImage out = new BufferedImage(sideways ? h : w, sideways ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(image, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** The picture as plain RGB (what JPEG keeps). */
    private static BufferedImage rgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** A JPEG's EXIF orientation (1: as it is, 3: upside down, 6 and 8: turned a quarter; 2, 4, 5, 7 mirrored). */
    static final class ExifOrientation {

        private ExifOrientation() {
        }

        static int of(byte[] jpeg) {
            try {
                int p = 2;
                while (p + 4 < jpeg.length && (jpeg[p] & 0xff) == 0xff) {
                    int marker = jpeg[p + 1] & 0xff;
                    int length = ((jpeg[p + 2] & 0xff) << 8) | (jpeg[p + 3] & 0xff);
                    if (marker == 0xda) {
                        return 1; // the picture's data: no EXIF before it
                    }
                    if (marker == 0xe1 && p + 10 < jpeg.length && jpeg[p + 4] == 'E' && jpeg[p + 5] == 'x'
                            && jpeg[p + 6] == 'i' && jpeg[p + 7] == 'f') {
                        return fromTiff(jpeg, p + 10, Math.min(jpeg.length, p + 2 + length));
                    }
                    p += 2 + length;
                }
            } catch (RuntimeException e) {
                // A broken header: as it is.
            }
            return 1;
        }

        private static int fromTiff(byte[] b, int start, int end) {
            boolean little = b[start] == 'I';
            int ifd = start + read(b, start + 4, 4, little);
            int entries = read(b, ifd, 2, little);
            for (int i = 0; i < entries; i++) {
                int e = ifd + 2 + i * 12;
                if (e + 12 > end) {
                    break;
                }
                if (read(b, e, 2, little) == 0x0112) {
                    int value = read(b, e + 8, 2, little);
                    return value >= 1 && value <= 8 ? value : 1;
                }
            }
            return 1;
        }

        private static int read(byte[] b, int at, int bytes, boolean little) {
            int v = 0;
            for (int k = 0; k < bytes; k++) {
                int byteValue = b[at + (little ? bytes - 1 - k : k)] & 0xff;
                v = (v << 8) | byteValue;
            }
            return v;
        }
    }
}
