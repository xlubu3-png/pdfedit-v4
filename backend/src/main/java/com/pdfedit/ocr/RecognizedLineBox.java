package com.pdfedit.ocr;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

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
 * font for new text, as wide as the line was and in the colour of its ink, over a patch that paints the scanned
 * letters - and only them - in the colour of the paper around them. Table rules and shaded bands that run
 * through the line's area are left as they are.
 */
@Component
public class RecognizedLineBox {

    /** Pixels per point the page is drawn at to read its colours and to lay the patch over its letters. */
    private static final float SCALE = 3f;
    /** Summed difference of the colour channels from the paper from which a pixel belongs to a letter (soft edges included). */
    private static final int LETTER_DIFFERENCE = 30;
    /** Pixels the patch reaches around each letter pixel, for the soft and blotchy edges of scanned letters. */
    private static final int SPREAD = 3;
    /**
     * A row of the line's area with one unbroken stretch of ink this long (a share of the width) is a table rule
     * or a shaded band, not letters; a column, one this long (a share of the height).
     */
    private static final double RULE_ROW = 0.5;
    private static final double RULE_COLUMN = 0.9;
    /** Distance between the baselines of a text box's lines, relative to the font size (as TextEditService draws). */
    private static final float LINE_SPACING = 1.2f;
    /** Font size relative to the recognized line's height, when the width does not ask for less. */
    private static final float SIZE_OF_HEIGHT = 0.8f;
    /** The same for the width of a vertical line, whose box reaches about half a letter past the letters. */
    private static final float SIZE_OF_WIDTH = 0.66f;
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
            int pageWidth = Math.max(1, (int) Math.floor(crop.getWidth() * SCALE));
            int pageHeight = Math.max(1, (int) Math.floor(crop.getHeight() * SCALE));

            // The line's pixels, and around them the few the patch may spread into (pixels of the whole page).
            int x0 = clamp((int) Math.floor((line.x() - crop.getLowerLeftX()) * SCALE), 0, pageWidth - 1);
            int y0 = clamp((int) Math.floor((crop.getUpperRightY() - (line.y() + line.height())) * SCALE), 0,
                    pageHeight - 1);
            Px core = new Px(x0, y0,
                    clamp((int) Math.ceil((line.x() + line.width() - crop.getLowerLeftX()) * SCALE), x0 + 1, pageWidth),
                    clamp((int) Math.ceil((crop.getUpperRightY() - line.y()) * SCALE), y0 + 1, pageHeight));
            Px area = new Px(Math.max(0, core.x0() - SPREAD), Math.max(0, core.y0() - SPREAD),
                    Math.min(pageWidth, core.x1() + SPREAD), Math.min(pageHeight, core.y1() + SPREAD));

            // Only that part of the page is drawn: the whole page this sharp is hundreds of megabytes for a large
            // drawing. In the picture of the part, the line starts SPREAD pixels in (less at the page's edge).
            BufferedImage image = new BufferedImage(area.width(), area.height(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, area.width(), area.height());
                g.translate(-area.x0(), -area.y0());
                new PDFRenderer(doc).renderPageToGraphics(pageIndex, g, SCALE);
            } finally {
                g.dispose();
            }
            Px coreInImage = new Px(core.x0() - area.x0(), core.y0() - area.y0(), core.x1() - area.x0(),
                    core.y1() - area.y0());
            Px areaInImage = new Px(0, 0, area.width(), area.height());

            Color paper = paper(pixels(image, coreInImage));
            List<Integer> letterPixels = new ArrayList<>();
            BufferedImage patch = patch(image, coreInImage, areaInImage, paper, letterPixels, line.vertical());
            Color ink = ink(letterPixels, paper);

            String text = line.text();
            float size;
            float x;
            float baseline;
            float rotation = 0;
            if (line.turned()) {
                // A line turned a quarter: the box is turned the same way, its text along the line. Down the page
                // the letters' tops face right and the line starts at the top; up it, they face left and it
                // starts at the bottom.
                boolean down = !line.upwards();
                size = round(fittedSize(doc, line.text(), line.width(), line.height()));
                rotation = down ? -90 : 90;
                float middle = line.x() + line.width() / 2f;
                x = round(down ? middle - size * MIDDLE_ABOVE_BASELINE : middle + size * MIDDLE_ABOVE_BASELINE);
                baseline = round(down ? line.y() + line.height() : line.y());
            } else if (line.vertical()) {
                // Letters one under the other, as many lines of one letter each, spread over the line's height.
                List<String> letterList = line.text().replace(" ", "").codePoints().mapToObj(Character::toString)
                        .toList();
                text = String.join("\n", letterList);
                int letters = Math.max(1, letterList.size());
                size = round(clamp(Math.min(line.width() * SIZE_OF_WIDTH, line.height() / (letters * LINE_SPACING)),
                        1f, 500f));
                x = round(line.x() + (line.width() - size) / 2f);
                float firstMiddle = line.y() + line.height() / 2f + (letters - 1) * size * LINE_SPACING / 2f;
                baseline = round(firstMiddle - size * MIDDLE_ABOVE_BASELINE);
            } else {
                size = round(fittedSize(doc, line.text(), line.height(), line.width()));
                x = round(line.x());
                baseline = round(line.y() + line.height() / 2f - size * MIDDLE_ABOVE_BASELINE);
            }
            AddedText.Cover cover = patch == null ? null
                    : new AddedText.Cover(round(crop.getLowerLeftX() + area.x0() / SCALE),
                            round(crop.getUpperRightY() - area.y1() / SCALE), round(area.width() / SCALE),
                            round(area.height() / SCALE), hex(paper), png(patch));
            return new AddedText(null, text, x, baseline, null, size, false, hex(ink), List.of(), cover, rotation);
        }
    }

    /** Pixel columns {@code x0} up to (not including) {@code x1}, rows {@code y0} up to {@code y1}. */
    record Px(int x0, int y0, int x1, int y1) {

        int width() {
            return x1 - x0;
        }

        int height() {
            return y1 - y0;
        }
    }

    /**
     * The patch over {@code area}: the paper's colour on the letters of the line (and a few pixels around them),
     * see-through everywhere else. Ink that runs as a long unbroken stretch across the line - a table rule, the
     * edge of a shaded band - is not letters and is not painted over. Null when there are no letters.
     *
     * @param letterPixels gets the colours of the letters' pixels
     * @param vertical     the line runs down the page: a rule along it is a column, one across it a row
     */
    static BufferedImage patch(BufferedImage image, Px core, Px area, Color paper, List<Integer> letterPixels,
            boolean vertical) {
        int w = core.width();
        int h = core.height();
        boolean[][] ink = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                ink[y][x] = difference(image.getRGB(core.x0() + x, core.y0() + y), paper) > LETTER_DIFFERENCE;
            }
        }
        boolean[] ruleRow = new boolean[h];
        for (int y = 0; y < h; y++) {
            int run = 0;
            int longest = 0;
            for (int x = 0; x < w; x++) {
                run = ink[y][x] ? run + 1 : 0;
                longest = Math.max(longest, run);
            }
            ruleRow[y] = longest >= (vertical ? RULE_COLUMN : RULE_ROW) * w;
        }
        boolean[] ruleColumn = new boolean[w];
        for (int x = 0; x < w; x++) {
            int run = 0;
            int longest = 0;
            for (int y = 0; y < h; y++) {
                run = ink[y][x] ? run + 1 : 0;
                longest = Math.max(longest, run);
            }
            ruleColumn[x] = longest >= (vertical ? RULE_ROW : RULE_COLUMN) * h;
        }

        boolean[][] paint = new boolean[area.height()][area.width()];
        boolean any = false;
        for (int y = 0; y < h; y++) {
            if (ruleRow[y]) {
                continue;
            }
            for (int x = 0; x < w; x++) {
                if (!ink[y][x] || ruleColumn[x]) {
                    continue;
                }
                any = true;
                letterPixels.add(image.getRGB(core.x0() + x, core.y0() + y) & 0xFFFFFF);
                int px = core.x0() + x - area.x0();
                int py = core.y0() + y - area.y0();
                for (int dy = -SPREAD; dy <= SPREAD; dy++) {
                    for (int dx = -SPREAD; dx <= SPREAD; dx++) {
                        int ax = px + dx;
                        int ay = py + dy;
                        if (ax >= 0 && ay >= 0 && ax < area.width() && ay < area.height()) {
                            paint[ay][ax] = true;
                        }
                    }
                }
            }
        }
        if (!any) {
            return null;
        }
        BufferedImage patch = new BufferedImage(area.width(), area.height(), BufferedImage.TYPE_INT_ARGB);
        int fill = 0xFF000000 | paper.getRGB() & 0xFFFFFF;
        for (int ay = 0; ay < area.height(); ay++) {
            for (int ax = 0; ax < area.width(); ax++) {
                if (paint[ay][ax] && !structure(ax + area.x0(), ay + area.y0(), core, ink, ruleRow, ruleColumn)) {
                    // Outside the line only what looks like paper is painted: ink there belongs to something else.
                    boolean inside = core.x0() <= ax + area.x0() && ax + area.x0() < core.x1()
                            && core.y0() <= ay + area.y0() && ay + area.y0() < core.y1();
                    if (inside || difference(image.getRGB(ax + area.x0(), ay + area.y0()), paper) <= LETTER_DIFFERENCE) {
                        patch.setRGB(ax, ay, fill);
                    }
                }
            }
        }
        return patch;
    }

    /** Whether the page pixel is on a rule or band found inside the line (never painted over). */
    private static boolean structure(int x, int y, Px core, boolean[][] ink, boolean[] ruleRow, boolean[] ruleColumn) {
        int cx = x - core.x0();
        int cy = y - core.y0();
        if (cx < 0 || cy < 0 || cx >= core.width() || cy >= core.height()) {
            return false;
        }
        return ink[cy][cx] && (ruleRow[cy] || ruleColumn[cx]);
    }

    private static int difference(int rgb, Color paper) {
        return Math.abs((rgb >> 16 & 0xFF) - paper.getRed()) + Math.abs((rgb >> 8 & 0xFF) - paper.getGreen())
                + Math.abs((rgb & 0xFF) - paper.getBlue());
    }

    /** The picture as PNG, base64-encoded. */
    private static String png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /**
     * The size at which the text, in the default font, is as long as the line, but no taller than the line allows.
     *
     * @param across the line's thickness (its height, or the width of a turned line)
     * @param along  its length
     */
    private float fittedSize(PDDocument doc, String text, float across, float along) {
        float byHeight = across * SIZE_OF_HEIGHT;
        try {
            PDFont font = fontMatcher.loadStyled(doc, null, false).font();
            float unit = font.getStringWidth(text) / 1000f;
            if (unit > 0) {
                return clamp(Math.min(byHeight, along / unit), 1f, 500f);
            }
        } catch (IOException | RuntimeException noWidth) {
            // A letter the font does not have, or no font at all: the line's height alone decides.
        }
        return clamp(byHeight, 1f, 500f);
    }

    /** The colours of the page inside the area. */
    private static List<Integer> pixels(BufferedImage image, Px area) {
        List<Integer> pixels = new ArrayList<>(area.width() * area.height());
        for (int y = area.y0(); y < area.y1(); y++) {
            for (int x = area.x0(); x < area.x1(); x++) {
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
