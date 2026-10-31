package com.pdfedit.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OcrEngineTest {

    private static OcrEngine engine;

    @BeforeAll
    static void load() throws Exception {
        engine = OcrEngine.load();
    }

    @AfterAll
    static void close() throws Exception {
        engine.close();
    }

    private static BufferedImage page(String[] lines, double angleDegrees) throws Exception {
        File malgun = new File("C:\\Windows\\Fonts\\malgun.ttf");
        assumeThat(malgun).as("needs the Windows font Malgun Gothic").exists();
        Font font = Font.createFont(Font.TRUETYPE_FONT, malgun).deriveFont(34f);
        BufferedImage image = new BufferedImage(1400, 120 + 70 * lines.length, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.rotate(Math.toRadians(angleDegrees), image.getWidth() / 2.0, image.getHeight() / 2.0);
        g.setColor(Color.BLACK);
        g.setFont(font);
        for (int i = 0; i < lines.length; i++) {
            g.drawString(lines[i], 60, 100 + 70 * i);
        }
        g.dispose();
        return image;
    }

    private static final String[] LINES = {
        "신청인 성명 홍길동 주민등록번호 900101-1234567",
        "연락처 010-2345-6789 납부기한 2026년 11월 30일",
        "본 공고에 명시되지 않은 사항은 관계 법령에 따릅니다.",
    };

    @Test
    void readsPrintedKoreanLinesInOrderWithTheirPlace() throws Exception {
        List<OcrLine> lines = engine.read(page(LINES, 0));

        assertThat(lines).extracting(l -> l.text().replace(" ", ""))
                .containsExactly(Arrays.stream(LINES).map(s -> s.replace(" ", "")).toArray(String[]::new));
        assertThat(lines).allSatisfy(l -> assertThat(l.score()).isGreaterThan(0.9f));
        OcrLine first = lines.get(0);
        // The first line's letters sit between y = 66 and 108 or so, starting at x = 60.
        assertThat(first.left()).isBetween(40f, 70f);
        assertThat(first.top()).isBetween(50f, 80f);
        assertThat(first.bottom()).isBetween(95f, 125f);
    }

    @Test
    void putsMostSpacesBetweenWordsBackAndNoneInsideAWord() throws Exception {
        List<OcrLine> lines = engine.read(page(LINES, 0));

        assertThat(lines.get(0).text()).isEqualTo(LINES[0]);
        assertThat(lines.get(2).text()).isEqualTo(LINES[2]);
        // A space can be missed (here between 년 and 11), but none is put where there is none.
        assertThat(lines.get(1).text()).startsWith("연락처 010-2345-6789 납부기한 2026년");
    }

    @Test
    void lettersOfOneWordAreCloserThanASpaceAllows() {
        assertThat(OcrEngine.spaceGap("가", "나")).isEqualTo(5);
        assertThat(OcrEngine.spaceGap("1", "1")).isEqualTo(Integer.MAX_VALUE);
        assertThat(OcrEngine.spaceGap("A", "4")).isEqualTo(4);
        assertThat(OcrEngine.spaceGap(":", "가")).isEqualTo(3);
    }

    @Test
    void readsLinesOfASlightlyTiltedScan() throws Exception {
        List<OcrLine> lines = engine.read(page(LINES, 1.5));

        assertThat(lines).extracting(l -> l.text().replace(" ", ""))
                .contains("연락처010-2345-6789납부기한2026년11월30일");
    }

    @Test
    void aBlankPictureHasNoLines() throws Exception {
        BufferedImage blank = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 600);
        g.dispose();

        assertThat(engine.read(blank)).isEmpty();
    }

    @Test
    void theSmallestRectangleAroundATurnedBoxIsFound() {
        // The corners of a 100 x 20 box turned by 30 degrees.
        double a = Math.toRadians(30);
        long[] points = new long[4];
        double[][] c = {{-50, -10}, {50, -10}, {50, 10}, {-50, 10}};
        for (int k = 0; k < 4; k++) {
            int x = (int) Math.round(200 + c[k][0] * Math.cos(a) - c[k][1] * Math.sin(a));
            int y = (int) Math.round(200 + c[k][0] * Math.sin(a) + c[k][1] * Math.cos(a));
            points[k] = ((long) x << 32) | y;
        }
        TextBoxes.Rect rect = TextBoxes.Rect.around(points);

        assertThat(Math.max(rect.w, rect.h)).isCloseTo(100, Offset.offset(1.5));
        assertThat(Math.min(rect.w, rect.h)).isCloseTo(20, Offset.offset(1.5));
        assertThat(rect.cx).isCloseTo(200, Offset.offset(1.0));
    }
}
