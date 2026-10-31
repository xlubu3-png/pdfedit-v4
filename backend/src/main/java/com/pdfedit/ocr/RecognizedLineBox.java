package com.pdfedit.ocr;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import com.pdfedit.service.PageNotEditableException;
import com.pdfedit.text.AddedText;
import com.pdfedit.text.FontMatcher;

/**
 * Turns a line recognized on a scan into a text box that can be edited: the recognized letters in the app's
 * font for new text, as wide as the line was and in the colour of its ink, over a patch in the colour of the
 * paper around it that hides the scanned letters.
 */
@Component
public class RecognizedLineBox {

    /** Pixels per point the page is drawn at to read its colours. */
    private static final float SCALE = 2f;
    /** How much of the line's height the cover reaches beyond it on every side (the box is drawn snug). */
    private static final float COVER_MARGIN = 0.12f;
    /** Font size relative to the recognized line's height, when the width does not ask for less. */
    private static final float SIZE_OF_HEIGHT = 0.8f;
    /** How far above the baseline the middle of a Hangul letter sits, relative to the font size. */
    private static final float MIDDLE_ABOVE_BASELINE = 0.36f;
    /** Summed difference of the colour channels from the paper from which a pixel is ink. */
    private static final int INK_DIFFERENCE = 150;
    /** Colour channels are grouped by this much to find the paper's colour in a noisy (JPEG) scan. */
    private static final int BUCKET = 24;

    private final FontMatcher fontMatcher;

    public RecognizedLineBox(FontMatcher fontMatcher) {
        this.fontMatcher = fontMatcher;
    }

    /** The box for {@code line} of page {@code pageIndex}; its id is left for the browser to choose. */
    public AddedText of(byte[] pdf, int pageIndex, OcrText line) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage page = doc.getPage(pageIndex);
            if (page.getRotation() % 360 != 0) {
                throw new PageNotEditableException("회전된 페이지의 글자는 고쳐 쓸 수 없습니다.");
            }
            PDRectangle crop = page.getCropBox();
            BufferedImage image = new PDFRenderer(doc).renderImage(pageIndex, SCALE);

            float margin = line.height() * COVER_MARGIN;
            AddedText.Cover area = new AddedText.Cover(round(line.x() - margin), round(line.y() - margin),
                    round(line.width() + 2 * margin), round(line.height() + 2 * margin), null);
            List<Integer> pixels = pixels(image, crop, area);
            Color paper = paper(pixels);
            Color ink = ink(pixels, paper);

            float size = round(fittedSize(doc, line));
            float baseline = round(line.y() + line.height() / 2f - size * MIDDLE_ABOVE_BASELINE);
            AddedText.Cover cover = new AddedText.Cover(area.x(), area.y(), area.width(), area.height(), hex(paper));
            return new AddedText(null, line.text(), round(line.x()), baseline, null, size, false, hex(ink), List.of(),
                    cover);
        }
    }

    /** The size at which the text, in the default font, is as wide as the line, but no taller than the line allows. */
    private float fittedSize(PDDocument doc, OcrText line) {
        float byHeight = line.height() * SIZE_OF_HEIGHT;
        try {
            PDFont font = fontMatcher.loadStyled(doc, null, false).font();
            float unit = font.getStringWidth(line.text()) / 1000f;
            if (unit > 0) {
                return clamp(Math.min(byHeight, line.width() / unit), 1f, 500f);
            }
        } catch (IOException | RuntimeException noWidth) {
            // A letter the font does not have, or no font at all: the line's height alone decides.
        }
        return clamp(byHeight, 1f, 500f);
    }

    /** The colours of the page inside the area. */
    private static List<Integer> pixels(BufferedImage image, PDRectangle crop, AddedText.Cover area) {
        int x0 = clamp((int) Math.floor((area.x() - crop.getLowerLeftX()) * SCALE), 0, image.getWidth() - 1);
        int x1 = clamp((int) Math.ceil((area.x() + area.width() - crop.getLowerLeftX()) * SCALE), x0 + 1,
                image.getWidth());
        int y0 = clamp((int) Math.floor((crop.getUpperRightY() - (area.y() + area.height())) * SCALE), 0,
                image.getHeight() - 1);
        int y1 = clamp((int) Math.ceil((crop.getUpperRightY() - area.y()) * SCALE), y0 + 1, image.getHeight());
        List<Integer> pixels = new ArrayList<>((x1 - x0) * (y1 - y0));
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                pixels.add(image.getRGB(x, y) & 0xFFFFFF);
            }
        }
        return pixels;
    }

    /**
     * The paper's colour: the commonest colour, once colours a little apart are taken as one (a scan's paper is
     * never one exact colour), averaged over the pixels that have it. When one exact colour makes up most of
     * them (paper drawn as a flat fill) it is that colour: an average of 255 and the soft edges of the letters
     * would be 254, a faint grey patch on white paper.
     */
    static Color paper(List<Integer> pixels) {
        Map<Integer, long[]> buckets = new HashMap<>();
        Map<Integer, Integer> exact = new HashMap<>();
        int bestBucket = -1;
        long bestCount = 0;
        for (int rgb : pixels) {
            int r = rgb >> 16 & 0xFF;
            int g = rgb >> 8 & 0xFF;
            int b = rgb & 0xFF;
            int bucket = r / BUCKET << 16 | g / BUCKET << 8 | b / BUCKET;
            long[] sum = buckets.computeIfAbsent(bucket, k -> new long[4]);
            sum[0] += r;
            sum[1] += g;
            sum[2] += b;
            sum[3]++;
            exact.merge(rgb, 1, Integer::sum);
            if (sum[3] > bestCount) {
                bestCount = sum[3];
                bestBucket = bucket;
            }
        }
        if (bestBucket < 0) {
            return Color.WHITE;
        }
        int commonest = -1;
        int commonestCount = 0;
        for (Map.Entry<Integer, Integer> entry : exact.entrySet()) {
            int rgb = entry.getKey();
            boolean inBucket = ((rgb >> 16 & 0xFF) / BUCKET << 16 | (rgb >> 8 & 0xFF) / BUCKET << 8
                    | (rgb & 0xFF) / BUCKET) == bestBucket;
            if (inBucket && entry.getValue() > commonestCount) {
                commonest = rgb;
                commonestCount = entry.getValue();
            }
        }
        if (commonestCount * 2 > bestCount) {
            return new Color(commonest);
        }
        long[] best = buckets.get(bestBucket);
        return new Color((int) (best[0] / best[3]), (int) (best[1] / best[3]), (int) (best[2] / best[3]));
    }

    /**
     * The ink's colour: the darker half of the pixels that clearly differ from the paper, averaged (the lighter
     * half are the soft edges of the letters). Black when there is no ink to see.
     */
    static Color ink(List<Integer> pixels, Color paper) {
        List<Integer> ink = new ArrayList<>();
        for (int rgb : pixels) {
            int difference = Math.abs((rgb >> 16 & 0xFF) - paper.getRed()) + Math.abs((rgb >> 8 & 0xFF) - paper.getGreen())
                    + Math.abs((rgb & 0xFF) - paper.getBlue());
            if (difference > INK_DIFFERENCE) {
                ink.add(rgb);
            }
        }
        if (ink.isEmpty()) {
            return Color.BLACK;
        }
        ink.sort((a, b) -> Integer.compare(luminance(a), luminance(b)));
        List<Integer> darker = ink.subList(0, Math.max(1, ink.size() / 2));
        long r = 0;
        long g = 0;
        long b = 0;
        for (int rgb : darker) {
            r += rgb >> 16 & 0xFF;
            g += rgb >> 8 & 0xFF;
            b += rgb & 0xFF;
        }
        int n = darker.size();
        return new Color((int) (r / n), (int) (g / n), (int) (b / n));
    }

    private static int luminance(int rgb) {
        return (rgb >> 16 & 0xFF) * 299 + (rgb >> 8 & 0xFF) * 587 + (rgb & 0xFF) * 114;
    }

    static String hex(Color colour) {
        return String.format("#%02x%02x%02x", colour.getRed(), colour.getGreen(), colour.getBlue());
    }

    private static float round(float value) {
        return Math.round(value * 100f) / 100f;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
