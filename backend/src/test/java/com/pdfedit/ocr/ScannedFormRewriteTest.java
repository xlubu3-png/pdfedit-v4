package com.pdfedit.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.Map;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.pdfedit.service.PageEdits;
import com.pdfedit.service.TextEditService;
import com.pdfedit.text.AddedText;
import com.pdfedit.text.FontMatcher;

/**
 * A form's title as scanned in practice: set in a 명조 face with check boxes recognition cannot read, under a faint
 * table rule, the page a little tilted and saved as a JPEG. Rewriting one word of it changes that word alone, in a
 * face and size like the scan's.
 */
class ScannedFormRewriteTest {

    private static final FontMatcher MATCHER = new FontMatcher();
    private static final TextEditService EDITS = new TextEditService(MATCHER);
    private static final PageOcr OCR = new PageOcr(EDITS);
    private static final int WIDTH = 1654;
    private static final int HEIGHT = 2339;
    /** The title: drawn 34 pixels high on a 200 dpi page, from x 200 on the baseline at y 400 (before the tilt). */
    private static final String BEFORE = "▣외래 □입원(□퇴원 □중간) 진료비 ";
    private static final String WORD = "계산서";
    private static final String AFTER = " · 영수증";
    private static final float PIXELS = 34;
    private static final int LEFT = 200;
    private static final int BASELINE = 400;
    /** Degrees the page is turned by, as a sheet fed a little askew. */
    private static final double TILT = 0.5;

    private static byte[] pdf;
    private static int wordFrom;
    private static int wordTo;

    @BeforeAll
    static void scan() throws Exception {
        File file = new File("C:/Windows/Fonts/batang.ttc");
        assumeThat(file).as("needs the Windows font batang.ttc").exists();
        Font batang = null;
        for (Font font : Font.createFonts(file)) {
            if ("Batang".equals(font.getPSName())) {
                batang = font.deriveFont(PIXELS);
            }
        }
        assumeThat(batang).as("Batang in batang.ttc").isNotNull();
        BufferedImage page = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setColor(new Color(0xF8F8F8));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.rotate(Math.toRadians(TILT), WIDTH / 2.0, HEIGHT / 2.0);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // A faint table rule just above the letters, as long as the title.
        g.setColor(new Color(0xC8C8C8));
        g.setStroke(new BasicStroke(3f));
        g.drawLine(LEFT - 40, BASELINE - 38, LEFT + 1100, BASELINE - 38);
        g.setColor(new Color(0x303030));
        g.setFont(batang);
        String title = BEFORE + WORD + AFTER;
        g.drawString(title, LEFT, BASELINE);
        wordFrom = LEFT + g.getFontMetrics().stringWidth(BEFORE);
        wordTo = wordFrom + g.getFontMetrics().stringWidth(WORD);
        g.dispose();

        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.6f);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(jpeg)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(page, null, null), param);
        } finally {
            writer.dispose();
        }
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage pdfPage = new PDPage(PDRectangle.A4);
            doc.addPage(pdfPage);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
                cs.drawImage(JPEGFactory.createFromByteArray(doc, jpeg.toByteArray()), 0, 0,
                        PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
    }

    @AfterAll
    static void close() throws Exception {
        OCR.destroy();
    }

    private static BufferedImage render(byte[] document) throws Exception {
        try (PDDocument doc = Loader.loadPDF(document)) {
            return new PDFRenderer(doc).renderImageWithDPI(0, 200);
        }
    }

    /** Pixels between columns {@code from} and {@code to} of the title's rows that clearly differ. */
    private static int differing(BufferedImage a, BufferedImage b, int from, int to) {
        int count = 0;
        for (int y = BASELINE - 60; y < BASELINE + 30; y++) {
            for (int x = Math.max(0, from); x < Math.min(to, Math.min(a.getWidth(), b.getWidth())); x++) {
                if (Math.abs((a.getRGB(x, y) >> 8 & 0xFF) - (b.getRGB(x, y) >> 8 & 0xFF)) > 40) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void oneWordOfATiltedTitleIsRewrittenAloneInAFaceAndSizeLikeTheScans() throws Exception {
        List<OcrText> lines = OCR.recognize(pdf, 0).lines();
        OcrText title = lines.stream().filter(l -> l.text().contains(WORD)).findFirst().orElseThrow();

        AddedText box = new RecognizedLineBox(MATCHER).of(pdf, 0, title, lines);

        assertThat(box.fontFamily()).as("a 명조 face, as the scan's").containsAnyOf("Batang", "명조", "MyeongJo",
                "Myeongjo");
        float size = PIXELS * 72 / 200f;
        assertThat(box.fontSize()).as("about the scan's size").isBetween(size * 0.88f, size * 1.12f);

        AddedText changed = new AddedText("c", box.text().replace(WORD, "상상송"), box.x(), box.y(),
                box.fontFamily(), box.fontSize(), box.bold(), box.color(), List.of(), box.cover(), box.rotation(),
                box.charSpacing());
        BufferedImage scan = render(pdf);
        BufferedImage edited = render(EDITS.buildSinglePage(pdf, 0, new PageEdits(Map.of(), List.of(changed))));

        assertThat(differing(scan, edited, 0, wordFrom - 12))
                .as("the check boxes and words before it are left as scanned").isLessThan(20);
        assertThat(differing(scan, edited, wordTo + 12, WIDTH)).as("the words after it are left as scanned")
                .isLessThan(20);
        assertThat(differing(scan, edited, wordFrom, wordTo)).as("the word is redrawn").isPositive();
        // Where the old word was, nothing is left round the new letters: none of the specks lighter than the paper
        // that a compressed scan rings its letters with, nor any the patch's edges would make when it is smoothed.
        int lighter = 0;
        int lighterBefore = 0;
        for (int y = BASELINE - 30; y < BASELINE + 12; y++) {
            for (int x = wordFrom; x < wordTo; x++) {
                lighter += (edited.getRGB(x, y) >> 8 & 0xFF) > 0xF8 + 4 ? 1 : 0;
                lighterBefore += (scan.getRGB(x, y) >> 8 & 0xFF) > 0xF8 + 4 ? 1 : 0;
            }
        }
        assertThat(lighterBefore).as("the scan rings its letters").isGreaterThan(100);
        assertThat(lighter).as("specks lighter than the paper left where the old word was")
                .isLessThan(lighterBefore / 5);
    }
}
