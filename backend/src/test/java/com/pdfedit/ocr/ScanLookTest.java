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

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.pdfedit.service.TextEditService;
import com.pdfedit.text.AddedText;
import com.pdfedit.text.FontMatcher;

/** A rewritten scan line takes the look of the scan: its font, size, colour, length and spaces. */
class ScanLookTest {

    private static final FontMatcher MATCHER = new FontMatcher();
    private static final PageOcr OCR = new PageOcr(new TextEditService(MATCHER));
    private static final String TEXT = "반응시설 반응기 용량 8m3";
    /** Drawn 34 pixels high on a 200 dpi page. */
    private static final float SIZE = 34 * 72 / 200f;

    @AfterAll
    static void close() throws Exception {
        OCR.destroy();
    }

    /** A page that is a 200 dpi picture of {@code text} in the font, in dark grey. */
    private static byte[] scan(Font font, String text) throws Exception {
        BufferedImage scan = new BufferedImage(1654, 2339, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scan.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1654, 2339);
        g.setColor(new Color(0x404040));
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font.deriveFont(34f));
        g.drawString(text, 300, 400);
        g.dispose();
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

    private static Font windowsFont(String file, String name) throws Exception {
        File path = new File("C:/Windows/Fonts/" + file);
        assumeThat(path).as("needs the Windows font " + file).exists();
        for (Font font : Font.createFonts(path)) {
            if (name == null || name.equals(font.getPSName())) {
                return font;
            }
        }
        throw new IllegalStateException(name + " not in " + file);
    }

    @ParameterizedTest
    @CsvSource({"gulim.ttc, Gulim, Gulim", "batang.ttc, Batang, Batang", "malgun.ttf, , Malgun Gothic"})
    void theFontTheScanWasSetInIsChosenAtItsSizeAndColour(String file, String name, String family) throws Exception {
        byte[] pdf = scan(windowsFont(file, name), TEXT);
        OcrText line = OCR.recognize(pdf, 0).lines().get(0);

        AddedText box = new RecognizedLineBox(MATCHER).of(pdf, 0, line);

        assertThat(box.fontFamily()).isEqualTo(family);
        assertThat(box.bold()).isFalse();
        assertThat(box.fontSize()).isCloseTo(SIZE, Offset.offset(SIZE * 0.05f));
        Color ink = Color.decode(box.color());
        assertThat(List.of(ink.getRed(), ink.getGreen(), ink.getBlue())).as("the scan's dark grey, not black")
                .allSatisfy(channel -> assertThat(channel).isBetween(0x40 - 16, 0x40 + 16));
        assertThat(box.text()).as("spaced as the scan is, whatever recognition made of the gaps").isEqualTo(TEXT);
        // Its first letter starts where the scan's did: 300 pixels in, at 200 dpi.
        assertThat(box.x()).isCloseTo(300 * 72 / 200f, Offset.offset(1.5f));
        assertThat(box.y()).isCloseTo(PDRectangle.A4.getHeight() - 400 * 72 / 200f, Offset.offset(1f));
    }

    /** Rows of a table set in one font at one size: rewritten each in the same font and size. */
    @Test
    void linesOfAPageAreRewrittenInOneFontAndSize() throws Exception {
        Font gulim = windowsFont("gulim.ttc", "Gulim");
        String[] rows = {"반응시설 반응기", "중화시설 반응기", "여과 세척시설", "분쇄 분급기 설비", "건조시설 건조기"};
        BufferedImage scan = new BufferedImage(1654, 2339, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scan.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1654, 2339);
        g.setColor(new Color(0x303030));
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(gulim.deriveFont(30f));
        for (int i = 0; i < rows.length; i++) {
            g.drawString(rows[i], 300, 400 + 80 * i);
        }
        g.dispose();
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(LosslessFactory.createFromImage(doc, scan), 0, 0, PDRectangle.A4.getWidth(),
                        PDRectangle.A4.getHeight());
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        List<OcrText> lines = OCR.recognize(pdf, 0).lines();
        RecognizedLineBox boxes = new RecognizedLineBox(MATCHER);

        List<AddedText> rewritten = new java.util.ArrayList<>();
        for (OcrText line : lines) {
            rewritten.add(boxes.of(pdf, 0, line, lines));
        }

        assertThat(rewritten).hasSize(rows.length);
        assertThat(rewritten).extracting(AddedText::fontFamily).as("one font for the page").containsOnly(
                rewritten.get(0).fontFamily());
        assertThat(rewritten).extracting(AddedText::fontSize).as("one size for its rows")
                .containsOnly(rewritten.get(0).fontSize());
        assertThat(rewritten).extracting(AddedText::color).as("one colour").containsOnly(rewritten.get(0).color());
        assertThat(rewritten.get(0).fontSize()).isCloseTo(30 * 72 / 200f, Offset.offset(30 * 72 / 200f * 0.06f));
    }

    @Test
    void spacesFollowTheScansGapsNotRecognitions() {
        // Letters 10 pixels wide, 6 apart within a word and 22 apart between words: "가나 다라".
        boolean[][] solid = new boolean[20][120];
        int[] lefts = {4, 20, 52, 68};
        for (int left : lefts) {
            for (int y = 2; y < 18; y++) {
                for (int x = left; x < left + 10; x++) {
                    solid[y][x] = true;
                }
            }
        }
        ScanLook.Ink ink = ScanLook.Ink.of(solid);
        // Recognition read a space after the first letter and none between the words.
        List<Float> centers = List.of(9 / 120f, 17 / 120f, 25 / 120f, 57 / 120f, 73 / 120f);

        assertThat(ScanLook.spacedAsScanned("가 나다라", centers, ink, 120)).isEqualTo("가나 다라");
        assertThat(ScanLook.spacedAsScanned("가나다", List.of(9 / 120f, 25 / 120f), ink, 120))
                .as("no places for every letter: left as read").isEqualTo("가나다");
    }
}
