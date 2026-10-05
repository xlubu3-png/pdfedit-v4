package com.pdfedit.service;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

/**
 * Hides the original letters of an edited line without disturbing what is around them. It finds
 * where the letters' ink is by comparing the page with and without its text, and covers just that
 * (plus a thin margin for anti-aliasing) with what the page looks like there without text: a plain
 * fill when that is one colour, otherwise a copy of the text-free picture, so a table rule that
 * runs next to the letters stays exactly as thick as it was.
 */
final class TextCover {

    /** Summed difference of the colour channels from which a pixel counts as "the text was here". */
    private static final int INK_THRESHOLD = 24;
    /** Pixels added around the ink, for the soft edge letters have when drawn. */
    private static final int MARGIN = 2;
    /** Share of one colour from which an area is treated as a flat fill. */
    private static final double FLAT = 0.985;

    private TextCover() {
    }

    /** A pixel area: columns {@code x0} up to (not including) {@code x1}, rows {@code y0} up to {@code y1}. */
    record Area(int x0, int y0, int x1, int y1) {

        int width() {
            return x1 - x0;
        }

        int height() {
            return y1 - y0;
        }
    }

    /** The smallest area of {@code within} that holds every pixel that differs between the two pictures, or null. */
    static Area inkBounds(BufferedImage withText, BufferedImage withoutText, Area within) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = within.y0(); y < within.y1(); y++) {
            for (int x = within.x0(); x < within.x1(); x++) {
                if (difference(withText.getRGB(x, y), withoutText.getRGB(x, y)) > INK_THRESHOLD) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? null : new Area(minX, minY, maxX + 1, maxY + 1);
    }

    /** The area grown by {@code margin} pixels each way, but never beyond {@code limit}. */
    static Area grow(Area area, int margin, Area limit) {
        return new Area(Math.max(limit.x0(), area.x0() - margin), Math.max(limit.y0(), area.y0() - margin),
                Math.min(limit.x1(), area.x1() + margin), Math.min(limit.y1(), area.y1() + margin));
    }

    /** The most common colour in the area and the share of the area that has exactly that colour. */
    static Map.Entry<Integer, Double> dominantColour(BufferedImage image, Area area) {
        Map<Integer, Integer> counts = new HashMap<>();
        int best = 0xFFFFFF;
        int bestCount = 0;
        for (int y = area.y0(); y < area.y1(); y++) {
            for (int x = area.x0(); x < area.x1(); x++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                int count = counts.merge(rgb, 1, Integer::sum);
                if (count > bestCount) {
                    bestCount = count;
                    best = rgb;
                }
            }
        }
        double share = area.width() * area.height() == 0 ? 1.0 : (double) bestCount / (area.width() * area.height());
        return Map.entry(best, share);
    }

    /**
     * Draws the cover for a line whose generous box is {@code (x, bottom, width, height)} in PDF user space.
     *
     * @param scale pixels per point of both pictures
     */
    static void paint(PDPageContentStream cs, PDDocument outDoc, BufferedImage withText, BufferedImage withoutText,
            PDRectangle crop, float scale, float x, float bottom, float width, float height) throws IOException {
        Area box = pixelArea(crop, scale, x, bottom, width, height, withText.getWidth(), withText.getHeight());
        Area ink = inkBounds(withText, withoutText, box);
        // No difference at all means the text could not be told apart (e.g. invisible text): cover the whole box.
        Area cover = ink == null ? box : grow(ink, MARGIN, box);

        Map.Entry<Integer, Double> dominant = dominantColour(withoutText, cover);
        float coverX = crop.getLowerLeftX() + cover.x0() / scale;
        float coverY = crop.getLowerLeftY() + crop.getHeight() - cover.y1() / scale;
        float coverW = cover.width() / scale;
        float coverH = cover.height() / scale;
        if (dominant.getValue() >= FLAT) {
            cs.setNonStrokingColor(new Color(dominant.getKey()));
            cs.addRect(coverX, coverY, coverW, coverH);
            cs.fill();
        } else {
            // Not a plain fill (a rule, shading or a picture runs through): put that very picture back.
            BufferedImage patch = new BufferedImage(cover.width(), cover.height(), BufferedImage.TYPE_INT_RGB);
            patch.getGraphics().drawImage(withoutText.getSubimage(cover.x0(), cover.y0(), cover.width(), cover.height()),
                    0, 0, null);
            cs.drawImage(LosslessFactory.createFromImage(outDoc, patch), coverX, coverY, coverW, coverH);
        }
    }

    static Area pixelArea(PDRectangle crop, float scale, float x, float bottom, float width, float height,
            int imageWidth, int imageHeight) {
        int x0 = clamp((int) Math.floor((x - crop.getLowerLeftX()) * scale), 0, imageWidth - 1);
        int x1 = clamp((int) Math.ceil((x + width - crop.getLowerLeftX()) * scale), x0 + 1, imageWidth);
        int y0 = clamp((int) Math.floor((crop.getHeight() - (bottom + height - crop.getLowerLeftY())) * scale),
                0, imageHeight - 1);
        int y1 = clamp((int) Math.ceil((crop.getHeight() - (bottom - crop.getLowerLeftY())) * scale),
                y0 + 1, imageHeight);
        return new Area(x0, y0, x1, y1);
    }

    private static int difference(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
