package com.pdfedit.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import com.pdfedit.service.PageEdits;
import com.pdfedit.service.TextEditService;
import com.pdfedit.text.AddedText;
import com.pdfedit.text.FontMatcher;

/** A rewritten line of a scanned table: its letters are painted over, the table around them is not. */
class RecognizedLineBoxTest {

    /** The picture is the page at 200 dpi. */
    private static final int WIDTH = 1654;
    private static final int HEIGHT = 2339;
    private static final String TEXT = "명칭 코리아피티지(주)";
    private static final int GREY = 0x9a9a9a;
    /** Rows of the grey band right above the line, of the rule under it, and the column of the rule left of it. */
    private static final int BAND_TOP = 240;
    private static final int BAND_BOTTOM = 284;
    private static final int RULE_ROW = 342;
    private static final int RULE_COLUMN = 188;
    private static final int TEXT_LEFT = 200;
    private static final int BASELINE = 325;

    private final FontMatcher fontMatcher = new FontMatcher();
    private final TextEditService textEditService = new TextEditService(fontMatcher);

    private static BufferedImage scan(int[] textRight) throws Exception {
        File malgun = new File("C:\\Windows\\Fonts\\malgun.ttf");
        assumeThat(malgun).as("needs the Windows font Malgun Gothic").exists();
        BufferedImage scan = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scan.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(new Color(GREY));
        g.fillRect(100, BAND_TOP, 1400, BAND_BOTTOM - BAND_TOP);
        g.setColor(Color.BLACK);
        g.fillRect(100, RULE_ROW, 1400, 3);
        g.fillRect(RULE_COLUMN, BAND_BOTTOM, 3, 200);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(Font.createFont(Font.TRUETYPE_FONT, malgun).deriveFont(36f));
        g.drawString(TEXT, TEXT_LEFT, BASELINE);
        textRight[0] = TEXT_LEFT + g.getFontMetrics().stringWidth(TEXT);
        g.dispose();
        return scan;
    }

    private static byte[] pdfOf(BufferedImage scan) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(LosslessFactory.createFromImage(doc, scan), 0, 0, PDRectangle.A4.getWidth(),
                        PDRectangle.A4.getHeight());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static int luminance(int rgb) {
        return ((rgb >> 16 & 0xFF) * 299 + (rgb >> 8 & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
    }

    @Test
    void theLettersArePaintedOverButTheGreyBandAndTheRulesAroundThemStay() throws Exception {
        int[] textRight = new int[1];
        BufferedImage scan = scan(textRight);
        byte[] pdf = pdfOf(scan);
        // Recognition draws its boxes generously: this one reaches into the band above and over both rules.
        float k = PDRectangle.A4.getHeight() / HEIGHT;
        OcrText line = new OcrText(TEXT, (RULE_COLUMN - 6) * k, PDRectangle.A4.getHeight() - (RULE_ROW + 8) * k,
                (textRight[0] + 12 - (RULE_COLUMN - 6)) * k, (RULE_ROW + 8 - (BAND_BOTTOM - 10)) * k, 0.99f, null);

        AddedText box = new RecognizedLineBox(fontMatcher).of(pdf, 0, line);

        assertThat(box.cover().patch()).isNotNull();
        assertThat(box.cover().color()).isEqualTo("#ffffff");
        // A viewer that smooths the patch blends colours before transparency: see-through pixels must be paper too.
        BufferedImage patch = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(
                java.util.Base64.getDecoder().decode(box.cover().patch())));
        int clear = 0;
        for (int y = 0; y < patch.getHeight(); y++) {
            for (int x = 0; x < patch.getWidth(); x++) {
                int argb = patch.getRGB(x, y);
                if (argb >>> 24 == 0) {
                    clear++;
                    assertThat(argb & 0xFFFFFF).isEqualTo(0xFFFFFF);
                }
            }
        }
        assertThat(clear).isPositive();
        AddedText coverOnly = new AddedText("c", "", box.x(), box.y(), null, box.fontSize(), false, box.color(),
                List.of(), box.cover());
        byte[] covered = textEditService.buildSinglePage(pdf, 0, new PageEdits(Map.of(), List.of(coverOnly)));
        BufferedImage after;
        try (PDDocument doc = Loader.loadPDF(covered)) {
            after = new PDFRenderer(doc).renderImageWithDPI(0, 200);
        }

        int letters = 0;
        int lettersLeft = 0;
        for (int y = BAND_BOTTOM + 4; y < RULE_ROW - 3; y++) {
            for (int x = TEXT_LEFT; x < textRight[0]; x++) {
                if (luminance(scan.getRGB(x, y)) < 128) {
                    letters++;
                }
                if (luminance(after.getRGB(x, y)) < 128) {
                    lettersLeft++;
                }
            }
        }
        assertThat(letters).as("the scan has letters there").isGreaterThan(500);
        assertThat(lettersLeft).as("dark pixels left where the letters were").isLessThan(letters / 100);

        for (int x = TEXT_LEFT; x < textRight[0]; x += 7) {
            assertThat(luminance(after.getRGB(x, BAND_BOTTOM - 3))).as("the grey band above, x=" + x)
                    .isBetween(luminance(GREY) - 12, luminance(GREY) + 12);
            assertThat(luminance(after.getRGB(x, RULE_ROW + 1))).as("the rule under the line, x=" + x).isLessThan(80);
        }
        for (int y = BAND_BOTTOM + 6; y < RULE_ROW - 2; y += 5) {
            assertThat(luminance(after.getRGB(RULE_COLUMN + 1, y))).as("the rule left of the line, y=" + y)
                    .isLessThan(80);
        }
    }

    @Test
    void aRuleSlantingAlongTheLineIsNotTakenForItsLetters() {
        // A line 300 pixels long whose letters (8 blocks 20 high) sit under a rule rising 4 rows over its length.
        boolean[][] ink = new boolean[40][300];
        for (int x = 0; x < 300; x++) {
            ink[4 - x / 75][x] = true;
            ink[5 - x / 75][x] = true;
        }
        for (int letter = 0; letter < 8; letter++) {
            for (int y = 12; y < 32; y++) {
                for (int x = 10 + 36 * letter; x < 30 + 36 * letter; x++) {
                    ink[y][x] = true;
                }
            }
        }

        boolean[][] letters = RecognizedLineBox.withoutSlantingRules(ink);

        for (int x = 0; x < 300; x++) {
            assertThat(letters[4 - x / 75][x] || letters[5 - x / 75][x]).as("the rule at x=" + x).isFalse();
        }
        for (int y = 12; y < 32; y++) {
            assertThat(letters[y][15]).as("a letter, y=" + y).isTrue();
        }
    }

    @Test
    void onlyTheLatestFewPagesWaitToHaveTheirFontTold() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(out);
            pdf = out.toByteArray();
        }
        RecognizedLineBox boxes = new RecognizedLineBox(fontMatcher);
        try {
            for (int page = 0; page < 20; page++) {
                boxes.prepare(pdf, page, List.of(new OcrText("반응시설 반응기 " + page, 50, 700, 200, 14, 0.99f,
                        null)));
            }

            assertThat(boxes.waitingPages()).as("a long document recognized page after page").isLessThanOrEqualTo(3);
        } finally {
            boxes.destroy();
        }
    }
}
