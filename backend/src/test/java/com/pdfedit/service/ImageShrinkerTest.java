package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import com.pdfedit.dto.PageSpec;
import com.pdfedit.text.FontMatcher;

/** "용량 줄이기": pictures stored again smaller, where that does not spoil them, and nothing else touched. */
class ImageShrinkerTest {

    /** The most pixels a picture keeps on an A4 page: its long side, 841.89 points, at 150 dpi. */
    private static final int A4_MOST = Math.round(PDRectangle.A4.getHeight() / 72f * ImageShrinker.DPI);

    /** A scan or photo: a gradient with grain, so it has thousands of colours (or gray levels). */
    private static BufferedImage photo(int width, int height, boolean gray) {
        BufferedImage image = new BufferedImage(width, height, gray ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = Math.min(255, Math.max(0, x * 255 / width + random.nextInt(31) - 15));
                int g = Math.min(255, Math.max(0, y * 255 / height + random.nextInt(31) - 15));
                int b = Math.min(255, Math.max(0, 128 + random.nextInt(31) - 15));
                image.setRGB(x, y, gray ? new Color(r, r, r).getRGB() : new Color(r, g, b).getRGB());
            }
        }
        return image;
    }

    /** A chart or screenshot: a few flat colours and sharp lines. */
    private static BufferedImage lineArt(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        for (int i = 0; i < width; i += 40) {
            g.drawLine(i, 0, i, height);
        }
        g.setColor(new Color(30, 90, 200));
        g.fillRect(width / 4, height / 4, width / 2, height / 3);
        g.dispose();
        return image;
    }

    /** A4 pages, each with its picture drawn over the whole page, and a line of text. */
    private static PDDocument pages(PictureMaker... pictures) throws IOException {
        PDDocument doc = new PDDocument();
        for (PictureMaker picture : pictures) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject image = picture.make(doc);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(72, 760);
                cs.showText("Text over the picture");
                cs.endText();
            }
        }
        return doc;
    }

    interface PictureMaker {
        PDImageXObject make(PDDocument doc) throws IOException;
    }

    private static List<PDImageXObject> images(PDPage page) throws IOException {
        List<PDImageXObject> found = new ArrayList<>();
        for (COSName name : page.getResources().getXObjectNames()) {
            if (page.getResources().getXObject(name) instanceof PDImageXObject image) {
                found.add(image);
            }
        }
        return found;
    }

    private static boolean isJpeg(PDImageXObject image) {
        return COSName.DCT_DECODE.equals(image.getCOSObject().getDictionaryObject(COSName.FILTER));
    }

    @Test
    void aLosslessColourScanBecomesAJpegOfAt150Dpi() throws IOException {
        try (PDDocument doc = pages(d -> LosslessFactory.createFromImage(d, photo(2480, 3508, false)))) {
            long before = images(doc.getPage(0)).get(0).getCOSObject().getLength();

            ImageShrinker.Report report = ImageShrinker.shrink(doc);

            PDImageXObject image = images(doc.getPage(0)).get(0);
            assertThat(isJpeg(image)).isTrue();
            assertThat(Math.max(image.getWidth(), image.getHeight())).isEqualTo(A4_MOST);
            assertThat(report.pictures()).isEqualTo(1);
            assertThat(report.before()).isEqualTo(before);
            assertThat(report.after()).isLessThan(before / 5);
        }
    }

    @Test
    void aHighQualityJpegIsStoredAgainSmaller() throws IOException {
        try (PDDocument doc = pages(d -> JPEGFactory.createFromImage(d, photo(2000, 2830, false), 0.98f))) {
            ImageShrinker.Report report = ImageShrinker.shrink(doc);

            PDImageXObject image = images(doc.getPage(0)).get(0);
            assertThat(isJpeg(image)).isTrue();
            assertThat(image.getHeight()).isEqualTo(A4_MOST);
            assertThat(report.after()).isLessThan(report.before());
        }
    }

    @Test
    void aGrayScanStaysGray() throws IOException {
        try (PDDocument doc = pages(d -> LosslessFactory.createFromImage(d, photo(2480, 3508, true)))) {
            ImageShrinker.shrink(doc);

            PDImageXObject image = images(doc.getPage(0)).get(0);
            assertThat(isJpeg(image)).isTrue();
            assertThat(image.getColorSpace()).isInstanceOf(PDDeviceGray.class);
        }
    }

    @Test
    void lineArtKeepsLosslessStorageAndOnlyLosesPixelsItDoesNotNeed() throws IOException {
        try (PDDocument doc = pages(d -> LosslessFactory.createFromImage(d, lineArt(4960, 7016)))) {
            ImageShrinker.shrink(doc);

            PDImageXObject image = images(doc.getPage(0)).get(0);
            assertThat(isJpeg(image)).isFalse();
            assertThat(image.getHeight()).isEqualTo(A4_MOST);
        }
    }

    @Test
    void smallTransparentAndBlackAndWhitePicturesAreLeftAlone() throws IOException {
        BufferedImage transparent = new BufferedImage(1200, 1200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = transparent.createGraphics();
        g.drawImage(photo(1200, 1200, false), 0, 0, null);
        g.dispose();
        BufferedImage blackAndWhite = new BufferedImage(2480, 3508, BufferedImage.TYPE_BYTE_BINARY);
        try (PDDocument doc = pages(d -> LosslessFactory.createFromImage(d, photo(300, 300, false)),
                d -> LosslessFactory.createFromImage(d, transparent),
                d -> LosslessFactory.createFromImage(d, blackAndWhite))) {
            List<Object> before = new ArrayList<>();
            for (PDPage page : doc.getPages()) {
                before.add(images(page).get(0).getCOSObject());
            }

            ImageShrinker.Report report = ImageShrinker.shrink(doc);

            assertThat(report.pictures()).isZero();
            for (int i = 0; i < 3; i++) {
                assertThat(images(doc.getPage(i)).get(0).getCOSObject()).isSameAs(before.get(i));
            }
        }
    }

    @Test
    void aPictureUsedOnTwoPagesIsStoredOnceForBoth() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject shared = LosslessFactory.createFromImage(doc, photo(2480, 3508, false));
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(shared, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
            }

            ImageShrinker.Report report = ImageShrinker.shrink(doc);

            assertThat(report.pictures()).isEqualTo(1);
            assertThat(images(doc.getPage(0)).get(0).getCOSObject()).isSameAs(images(doc.getPage(1)).get(0).getCOSObject());
        }
    }

    @Test
    void theTextOfThePageIsUntouched() throws IOException {
        try (PDDocument doc = pages(d -> LosslessFactory.createFromImage(d, photo(2480, 3508, false)))) {
            ImageShrinker.shrink(doc);

            assertThat(new PDFTextStripper().getText(doc)).contains("Text over the picture");
        }
    }

    @Test
    void anExportAskedToBeSmallIsSmallerAndSaysBy() throws IOException {
        PdfDocumentStore store = new PdfDocumentStore();
        PdfExportService export = new PdfExportService(store, new TextEditService(new FontMatcher()));
        byte[] scan;
        try (PDDocument doc = pages(d -> LosslessFactory.createFromImage(d, photo(2480, 3508, false)),
                d -> JPEGFactory.createFromImage(d, photo(2480, 3508, true), 0.95f));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.save(out);
            scan = out.toByteArray();
        }
        String id = store.store("scan.pdf", scan, 2);
        List<PageSpec> both = List.of(new PageSpec(id, 0, 0), new PageSpec(id, 1, 0));

        PdfExportService.Exported plain = export.exportWithReport(both, false, false);
        PdfExportService.Exported small = export.exportWithReport(both, false, true);

        assertThat(plain.shrink().pictures()).isZero();
        assertThat(small.shrink().pictures()).isEqualTo(2);
        assertThat(small.pdf().length).isLessThan(plain.pdf().length / 4);
        try (PDDocument out = Loader.loadPDF(small.pdf())) {
            assertThat(out.getNumberOfPages()).isEqualTo(2);
            assertThat(new PDFTextStripper().getText(out)).contains("Text over the picture");
        }
    }

    @Test
    void aSmallPictureExportIsMadeAtTheLowerResolution() throws IOException {
        PdfDocumentStore store = new PdfDocumentStore();
        PdfExportService export = new PdfExportService(store, new TextEditService(new FontMatcher()));
        byte[] scan;
        try (PDDocument doc = pages(d -> LosslessFactory.createFromImage(d, photo(1240, 1754, false)));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.save(out);
            scan = out.toByteArray();
        }
        String id = store.store("scan.pdf", scan, 1);

        byte[] usual = export.exportWithReport(List.of(new PageSpec(id, 0, 0)), true, false).pdf();
        byte[] small = export.exportWithReport(List.of(new PageSpec(id, 0, 0)), true, true).pdf();

        try (PDDocument a = Loader.loadPDF(usual); PDDocument b = Loader.loadPDF(small)) {
            // Pages are drawn whole pixels high, rounded down: 841.89 points at 200 and at 150 dpi.
            assertThat(images(a.getPage(0)).get(0).getHeight()).isEqualTo((int) (PDRectangle.A4.getHeight() * 200 / 72));
            assertThat(images(b.getPage(0)).get(0).getHeight()).isEqualTo((int) (PDRectangle.A4.getHeight() * 150 / 72));
        }
        assertThat(small.length).isLessThan(usual.length);
    }
}
