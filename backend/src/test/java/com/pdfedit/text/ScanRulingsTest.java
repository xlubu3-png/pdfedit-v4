package com.pdfedit.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

/** A scanned table's borders are found in the page's picture; letters, bold titles and shading are not borders. */
class ScanRulingsTest {

    /** Points per pixel at the resolution pages are looked at. */
    private static final float PPP = 72f / ScanRulings.DPI;

    private static BufferedImage white(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return image;
    }

    /** A 3 x 2 table (rows at 50, 100, 150 px; columns at 50, 250, 550 px) with text in it. */
    private static void table(Graphics2D g) {
        g.setColor(new Color(40, 40, 40));
        g.setStroke(new BasicStroke(2));
        for (int y : new int[] {50, 100, 150}) {
            g.drawLine(50, y, 550, y);
        }
        for (int x : new int[] {50, 250, 550}) {
            g.drawLine(x, 50, x, 150);
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        g.drawString("차대번호 KMHAB81CBNU123456", 60, 80);
    }

    private static List<Long> ys(RulingLines.Lines lines) {
        return lines.horizontal().stream().map(h -> Math.round(h.y() / PPP)).sorted().toList();
    }

    private static List<Long> xs(RulingLines.Lines lines) {
        return lines.vertical().stream().map(v -> Math.round(v.x() / PPP)).sorted().toList();
    }

    @Test
    void findsTheTablesBordersAndLeavesItsLettersOut() {
        BufferedImage image = white(600, 400);
        Graphics2D g = image.createGraphics();
        table(g);
        g.dispose();

        RulingLines.Lines lines = ScanRulings.of(image, PPP);

        assertThat(ys(lines)).containsExactly(50L, 100L, 150L);
        assertThat(xs(lines)).containsExactly(50L, 250L, 550L);
        assertThat(lines.horizontal()).allSatisfy(h -> {
            assertThat(h.x1() / PPP).isCloseTo(49, within(3.0));
            assertThat(h.x2() / PPP).isCloseTo(551, within(3.0));
        });
    }

    @Test
    void aPageScannedAskewIsTurnedStraightAndItsTableFoundWhole() {
        BufferedImage page = white(800, 600);
        Graphics2D g = page.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // The sheet fed 2 degrees askew: everything on it turned 2 degrees anticlockwise about the middle.
        g.setTransform(AffineTransform.getRotateInstance(Math.toRadians(-2), 400, 300));
        g.setColor(new Color(30, 30, 30));
        g.setStroke(new BasicStroke(2));
        int[] rows = {150, 200, 250, 300, 350, 400};
        for (int y : rows) {
            g.drawLine(100, y, 700, y);
        }
        for (int x : new int[] {100, 300, 500, 700}) {
            g.drawLine(x, 150, x, 400);
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        for (int r = 0; r < 5; r++) {
            g.drawString("항목 " + r + "   220V/380V  1.13KW  Set", 110, 180 + r * 50);
        }
        g.dispose();

        RulingLines.Lines lines = ScanRulings.straightened(page, PPP, List.of());

        assertThat(Deskew.angle(page)).isCloseTo(2, within(0.3));
        assertThat(lines.turn()).isCloseTo(2, within(0.3));
        // Turned straight, each border is one whole line again.
        assertThat(lines.horizontal()).hasSize(6);
        assertThat(lines.vertical()).hasSize(4);
        assertThat(lines.horizontal()).allSatisfy(h -> assertThat((h.x2() - h.x1()) / PPP).isGreaterThan(560));
    }

    @Test
    void aStraightPageIsNotTurned() {
        BufferedImage image = white(600, 400);
        Graphics2D g = image.createGraphics();
        table(g);
        g.dispose();

        assertThat(Deskew.angle(image)).isZero();
        assertThat(ScanRulings.straightened(image, PPP, List.of()).turn()).isZero();
    }

    @Test
    void aDottedRowBorderMeetingTheTablesSidesIsALine() {
        BufferedImage image = white(600, 400);
        Graphics2D g = image.createGraphics();
        table(g);
        // A dotted border across the table at 125 px: 1-pixel dots 2 pixels apart.
        g.setColor(new Color(60, 60, 60));
        for (int x = 52; x < 549; x += 3) {
            g.fillRect(x, 125, 1, 1);
        }
        // A row of full stops in a cell is not a border.
        for (int x = 270; x < 300; x += 6) {
            g.fillRect(x, 140, 2, 2);
        }
        g.dispose();

        assertThat(ys(ScanRulings.of(image, PPP))).containsExactly(50L, 100L, 126L, 150L); // a 1-pixel row at 125 is centred at 125.5
    }

    @Test
    void aShadedHeaderRowWithNoLineDrawnRoundItIsPartedByItsEdges() {
        BufferedImage image = white(600, 400);
        Graphics2D g = image.createGraphics();
        table(g);
        // A grey header band above the table, its two cells parted by a white gap, with no border drawn.
        g.setColor(new Color(215, 215, 215));
        g.fillRect(50, 20, 198, 29);
        g.fillRect(253, 20, 296, 29);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString("구분", 120, 40);
        g.dispose();

        RulingLines.Lines lines = ScanRulings.of(image, PPP);

        // The band's top edge is a border, and the edges of its two cells part them.
        assertThat(ys(lines)).contains(20L);
        assertThat(xs(lines)).anySatisfy(x -> assertThat(x).isBetween(246L, 255L));
    }

    @Test
    void aThinLineScannedLightGreyIsFoundButABoldTitleIsNot() {
        BufferedImage image = white(600, 400);
        Graphics2D g = image.createGraphics();
        table(g);
        // A hairline border that came out light grey across two pixels.
        g.setColor(new Color(200, 200, 200));
        g.fillRect(50, 175, 500, 2);
        g.drawLine(50, 150, 50, 200);
        g.drawLine(550, 150, 550, 200);
        g.setColor(new Color(200, 200, 200));
        g.fillRect(50, 199, 500, 2);
        // A bold title: thick strokes are not borders.
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 60));
        g.drawString("자동차등록증", 120, 330);
        g.dispose();

        RulingLines.Lines lines = ScanRulings.of(image, PPP);

        assertThat(ys(lines)).contains(176L);
        assertThat(ys(lines)).allSatisfy(y -> assertThat(y).isLessThan(260L));
    }

    @Test
    void theBordersDrawnInsideAGreyShadedHeaderRowAreKept() {
        BufferedImage image = white(600, 400);
        Graphics2D g = image.createGraphics();
        // The header row shaded mid grey, darker than ink is taken to be, its cells parted by drawn borders.
        g.setColor(new Color(185, 185, 185));
        g.fillRect(50, 50, 500, 50);
        table(g);
        g.drawLine(400, 50, 400, 100);
        g.dispose();

        RulingLines.Lines lines = ScanRulings.of(image, PPP);

        assertThat(lines.vertical()).anySatisfy(v -> {
            assertThat(Math.round(v.x() / PPP)).isBetween(399L, 401L);
            assertThat(v.y1() / PPP).isLessThan(56.0);
        });
        assertThat(lines.vertical()).anySatisfy(v -> {
            assertThat(Math.round(v.x() / PPP)).isBetween(249L, 251L);
            assertThat(v.y1() / PPP).isLessThan(56.0);
        });
        assertThat(ys(lines)).contains(50L, 100L, 150L);
    }

    @Test
    void aShortBorderPartingOneRowOnlyIsKept() {
        BufferedImage image = white(600, 400);
        Graphics2D g = image.createGraphics();
        table(g);
        // A row 17 points high, parted in two by a border of its own height only.
        g.drawLine(50, 178, 550, 178);
        g.drawLine(50, 150, 50, 178);
        g.drawLine(550, 150, 550, 178);
        g.drawLine(330, 150, 330, 178);
        g.dispose();

        assertThat(xs(ScanRulings.of(image, PPP))).anySatisfy(x -> assertThat(x).isBetween(329L, 331L));
    }

    @Test
    void aPageOfOnlyTextHasNoBorders() {
        BufferedImage image = white(400, 200);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        for (int i = 0; i < 6; i++) {
            g.drawString("자동차등록증 一 二 三 Lorem ipsum 2025-04-21", 10, 30 + i * 28);
        }
        g.dispose();

        RulingLines.Lines lines = ScanRulings.of(image, PPP);

        assertThat(lines.horizontal()).isEmpty();
        assertThat(lines.vertical()).isEmpty();
    }
}
