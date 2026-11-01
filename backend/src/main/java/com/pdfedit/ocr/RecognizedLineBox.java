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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.pdfedit.service.PageNotEditableException;
import com.pdfedit.text.AddedText;
import com.pdfedit.text.FontMatcher;
import com.pdfedit.text.SystemFonts;

/**
 * Turns a line recognized on a scan into a text box that can be edited: the recognized letters in the app's
 * font for new text, as wide as the line was and in the colour of its ink, over a patch that paints the scanned
 * letters - and only them - in the colour of the paper around them. Table rules and shaded bands that run
 * through the line's area are left as they are.
 */
@Component
public class RecognizedLineBox implements DisposableBean {

    /** Pixels per point the page is drawn at to read its colours and to lay the patch over its letters. */
    private static final float SCALE = 3f;
    /** Summed difference of the colour channels from the paper from which a pixel belongs to a letter (soft edges included). */
    private static final int LETTER_DIFFERENCE = 30;
    /** Pixels the patch reaches around each letter pixel, for the soft and blotchy edges of scanned letters. */
    private static final int SPREAD = 3;
    /** ... and further, over pixels too little apart from the paper to be ink: a compressed scan's ringing. */
    private static final int RINGING = 10;
    /** How opaque the patch paints the ringing: as good as fully, but told apart from the paint over the letters. */
    private static final int RINGING_ALPHA = 0xFE;
    /** Pixels the patch's edge fades out over, from this opacity down. */
    private static final int FEATHER = 4;
    private static final int FEATHERED = 0xA0;
    /**
     * A row of the line's area with one unbroken stretch of ink this long (a share of the width) is a table rule
     * or a shaded band, not letters; a column, one this long (a share of the height).
     */
    private static final double RULE_ROW = 0.5;
    private static final double RULE_COLUMN = 0.9;
    /**
     * A stretch of a row this long (a share of the line's length) is longer than any letter's stroke: with the rows
     * this many above and below, it may be part of a rule slanting along the line.
     */
    private static final double RULE_PIECE = 0.08;
    private static final int SLANT_ROWS = 2;
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
    /** The same for the solid ink of a letter (not its soft edge), which fonts are compared against. */
    private static final int SOLID_DIFFERENCE = 100;
    /** Ink within this many pixels of the line box's edge across the line is not the line's: a rule, another row ... */
    private static final int EDGE = 1;
    /** ... when it is less than this share of the box tall (across the line). */
    private static final double EDGE_SPAN = 0.5;
    /**
     * Thin patches (this share of the box across the line) lying all in its outer band (this share at either side)
     * are not the line's either.
     */
    private static final double THIN = 0.12;
    private static final double OUTER = 0.2;
    /** Ink whose middle is further than this (a share of the distance between letters) from all letters was not read. */
    private static final double UNREAD = 0.9;
    /**
     * A check box is at least this share of the line's box tall (and no taller than it), as wide as tall within
     * this ratio, inked along this share of each side (in its outer {@link #BOX_BAND}) and with at most this share of
     * its ink in its middle half.
     */
    private static final double BOX_SIDE_LEAST = 0.3;
    private static final double BOX_SQUARE = 0.8;
    private static final double BOX_BAND = 0.2;
    private static final double BOX_SIDES = 0.7;
    private static final double BOX_MIDDLE = 0.03;
    /** Room left after ink recognition did not read, before the next letter (a share of the font size). */
    private static final double ROOM = 0.15;
    /** Pages whose fonts were compared, remembered for their next rewrites. */
    private static final int PAGES_REMEMBERED = 32;
    /** At most this many pages wait to have their font told in the background. */
    private static final int PAGES_WAITING = 3;
    /** At most this many of the page's other lines, read at least this surely and this long, judge its font. */
    private static final int PAGE_SAMPLES = 8;
    /** A line keeps a face of the other style only when it is this much more like it (0-1) than the page's font. */
    private static final double OTHER_FONT = 0.15;
    /** Faces fitting a page's lines within this of the best fit as well as it (a coarse scan tells them apart poorly). */
    private static final double TIE = 0.03;
    /**
     * The scan's letters covering this many times what the font's plain letters cover are bold; and this many
     * times, on a page found bold.
     */
    private static final double BOLD_ALONE = 1.35;
    private static final double BOLD_WITH_PAGE = 1.15;
    /** How much a font's fit drops per unit of the (natural) log of how much heavier or lighter it is than the scan. */
    private static final double WEIGHT_MISFIT = 0.3;
    /**
     * ... beyond this many times heavier or lighter: how heavy a scan's letters are is told only roughly (its blur and
     * the rectangle round its ink), and within it the letters' shapes tell faces apart better.
     */
    private static final double WEIGHT_SLACK = 1.4;
    /** Sizes this close (a share) to one of the page's lines' are that line's size. */
    private static final double SAME_SIZE = 0.08;
    /** Colours this close in lightness (0-255) to the page's text colour are that colour. */
    private static final int SAME_COLOUR = 40;
    private static final float SURE_LINE = 0.85f;
    private static final int SAMPLE_LETTERS = 4;
    /** A rewritten line's ink is lightened at most this far towards the paper (1 is the scan's own ink). */
    private static final double LIGHTEST_TONE = 0.85;
    /** Colour channels are grouped by this much to find the paper's colour in a noisy (JPEG) scan. */
    private static final int BUCKET = 24;
    /** Pixels this close (summed channel difference) to the paper's commonest colour are paper, specks and all. */
    private static final int PAPER_SPECKS = 36;

    private static final Logger log = LoggerFactory.getLogger(RecognizedLineBox.class);

    private final FontMatcher fontMatcher;

    public RecognizedLineBox(FontMatcher fontMatcher) {
        this.fontMatcher = fontMatcher;
    }

    /**
     * The font each page was found set in, kept for the page's next rewrites; while it is being told (in the
     * background after the page was recognized, or for a rewrite), the rewrite waits for it rather than tells it
     * again.
     */
    private final Map<String, CompletableFuture<PageFont>> pageFonts = new java.util.LinkedHashMap<>(16, 0.75f,
            true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CompletableFuture<PageFont>> eldest) {
            return size() > PAGES_REMEMBERED;
        }
    };

    /**
     * Pages waiting to have their font told, the latest first; at most {@link #PAGES_WAITING}, the oldest dropped
     * (recognizing a long document page after page would otherwise line up every page behind it).
     */
    private final java.util.Deque<Waiting> waiting = new java.util.ArrayDeque<>();

    private record Waiting(byte[] pdf, int pageIndex, List<OcrText> lines, String key) {
    }

    /** Tells pages' fonts in the background, one page at a time. */
    private final ExecutorService preparing = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "page-font");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /**
     * Tells the font of a recognized page in the background, so that the page's first rewrite need not: telling
     * it compares a dozen faces over several of its lines, which takes a few seconds.
     */
    public void prepare(byte[] pdf, int pageIndex, List<OcrText> pageLines) {
        List<OcrText> samples = samples(pageLines);
        if (samples.isEmpty()) {
            return;
        }
        String key = pageKey(pdf, pageIndex, samples);
        synchronized (pageFonts) {
            if (pageFonts.containsKey(key)) {
                return;
            }
        }
        // The page just recognized or opened is the one most likely rewritten next: it goes first.
        synchronized (waiting) {
            waiting.removeIf(page -> page.key().equals(key));
            waiting.addFirst(new Waiting(pdf, pageIndex, pageLines, key));
            while (waiting.size() > PAGES_WAITING) {
                waiting.removeLast();
            }
        }
        preparing.execute(this::prepareNext);
    }

    private void prepareNext() {
        Waiting next;
        synchronized (waiting) {
            next = waiting.pollFirst();
        }
        if (next == null) {
            return;
        }
        try (PDDocument doc = Loader.loadPDF(next.pdf())) {
            PDPage page = doc.getPage(next.pageIndex());
            if (page.getRotation() % 360 == 0) {
                pageFont(next.pdf(), new PDFRenderer(doc), next.pageIndex(), page.getCropBox(), next.lines(),
                        fontMatcher.documentFaces());
            }
        } catch (IOException | RuntimeException failed) {
            log.debug("The font of page {} could not be told ahead: {}", next.pageIndex(), failed.toString());
        }
    }

    /** The page's longest lines read surely, which tell its font. */
    private static List<OcrText> samples(List<OcrText> pageLines) {
        return pageLines.stream()
                .filter(l -> !l.vertical() && l.score() >= SURE_LINE
                        && l.text().replace(" ", "").length() >= SAMPLE_LETTERS)
                .sorted(java.util.Comparator.comparingInt((OcrText l) -> -l.text().length()))
                .limit(PAGE_SAMPLES).toList();
    }

    /** What a page's font is remembered by: the document (roughly), the page and the lines that tell it. */
    private static String pageKey(byte[] pdf, int pageIndex, List<OcrText> samples) {
        return pageIndex + ":" + pdf.length + ":" + java.util.Arrays.hashCode(
                java.util.Arrays.copyOf(pdf, Math.min(pdf.length, 4096))) + ":" + samples.hashCode();
    }

    /** Whether page {@code pageIndex}'s font is told or being told (for tests). */
    boolean prepared(byte[] pdf, int pageIndex, List<OcrText> pageLines) {
        synchronized (pageFonts) {
            return pageFonts.containsKey(pageKey(pdf, pageIndex, samples(pageLines)));
        }
    }

    /** How many pages wait to have their font told (for tests). */
    int waitingPages() {
        synchronized (waiting) {
            return waiting.size();
        }
    }

    /**
     * The document faces a scanned line is compared against are opened once the app is up, in the background:
     * opening a dozen font files takes a moment the first rewrite would otherwise wait for.
     */
    @EventListener(ApplicationReadyEvent.class)
    void openFacesAhead() {
        preparing.execute(() -> fontMatcher.documentFaces().forEach(ScanLook::awtFont));
    }

    @Override
    public void destroy() {
        preparing.shutdownNow();
    }

    /**
     * The font a page's text is set in: its family, whether its letters are bold, the sizes its lines are set at
     * (pixels of the scan, one per line looked at) and the colour its text is redrawn in. Null family when the
     * page has no line to tell by.
     */
    record PageFont(String family, boolean bold, List<Double> sizes, Color colour) {
    }

    /** The box for {@code line} of page {@code pageIndex}; its id is left for the browser to choose. */
    public AddedText of(byte[] pdf, int pageIndex, OcrText line) throws IOException {
        return of(pdf, pageIndex, line, List.of());
    }

    /**
     * The box for {@code line} of page {@code pageIndex}; its id is left for the browser to choose.
     *
     * @param pageLines the lines recognized on the page: the font is chosen to suit them as well, as a page is
     *                  usually set in one font (so rows of one table are not each rewritten in another)
     */
    public AddedText of(byte[] pdf, int pageIndex, OcrText line, List<OcrText> pageLines) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage page = doc.getPage(pageIndex);
            if (page.getRotation() % 360 != 0) {
                throw new PageNotEditableException("회전된 페이지의 글자는 고쳐 쓸 수 없습니다.");
            }
            PDRectangle crop = page.getCropBox();
            PDFRenderer renderer = new PDFRenderer(doc);
            Region region = region(renderer, pageIndex, crop, line);
            BufferedImage image = region.image();
            Px area = region.area();
            Px coreInImage = region.core();
            Px areaInImage = new Px(0, 0, area.width(), area.height());

            Color paper = paper(pixels(image, coreInImage));
            List<Integer> letterPixels = new ArrayList<>();
            boolean[][] solid = new boolean[coreInImage.height()][coreInImage.width()];
            List<int[]> unread = new ArrayList<>();
            BufferedImage patch = patch(image, coreInImage, areaInImage, paper, letterPixels, line.vertical(), solid,
                    line.text(), line.centers(), unread);
            Color ink = ink(letterPixels, paper);
            Color scannedInk = ink;

            String text = line.text();
            String family = null;
            boolean bold = false;
            float size;
            float x;
            float baseline;
            float rotation = 0;
            float spacing = 0;
            // A line of a tilted scan is measured levelled: its letters' height, their words and the faces are told
            // as if the sheet had been fed straight.
            Level level = line.vertical() ? null : Level.of(line.tiltDegrees(), solid);
            ScanLook.Ink scanInk = level == null ? null : ScanLook.Ink.of(level.solid());
            ScanLook.Ink unlevelledInk = line.vertical() ? null : ScanLook.Ink.of(solid);
            ScanLook.Look look = null;
            if (scanInk != null) {
                // Across the page: the words as the scan spaces them, in the installed font most like its letters
                // and the page's other lines.
                text = ScanLook.spacedAsScanned(line.text(), line.centers(), scanInk, coreInImage.width());
                List<SystemFonts.Face> faces = fontMatcher.documentFaces();
                PageFont pageFont = pageFont(pdf, renderer, pageIndex, crop, pageLines, faces);
                double darkness = darkness(image, coreInImage, unlevelledInk != null ? unlevelledInk : scanInk, paper);
                look = chosenLook(text, scanInk, faces, pageFont, heavy(darkness, paper, fullInk(letterPixels, paper)),
                        ScanLook.letterMiddles(line.text(), line.centers(), coreInImage.width()));
                if (look != null && !unread.isEmpty()) {
                    // Room for what recognition did not read (check boxes, symbols), which stays as scanned.
                    String roomy = withRoom(text, line.text(), line.centers(), coreInImage.width(), unread, scanInk,
                            look);
                    if (!roomy.equals(text)) {
                        ScanLook.Look refitted = ScanLook.at(roomy, scanInk, look.face(), look.sizePx());
                        if (refitted != null) {
                            text = roomy;
                            look = refitted;
                        }
                    }
                }
                if (look != null && look.face().family().equals(pageFont.family())) {
                    // Lines of one size on the page (rows of a table) get one size, not each its own few pixels
                    // off; and one colour.
                    ScanLook.Look sized = ScanLook.at(text, scanInk, look.face(),
                            pageSize(pageFont.sizes(), look.sizePx()));
                    look = sized != null ? sized : look;
                    ink = alike(toned(ink, paper, darkness, look.coverage()), pageFont.colour());
                } else if (look != null) {
                    ink = toned(ink, paper, darkness, look.coverage());
                }
            }
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
            } else if (look != null) {
                family = look.face().family();
                bold = look.face().bold();
                size = round(clamp((float) (look.sizePx() / SCALE), 1f, 500f));
                spacing = round((float) (look.spacingPx() / SCALE));
                x = round(crop.getLowerLeftX() + (float) (area.x0() + coreInImage.x0() + look.originX()) / SCALE);
                baseline = round(crop.getUpperRightY()
                        - (float) (area.y0() + coreInImage.y0() + level.unlevelled(look.originX(), look.baselineY()))
                                / SCALE);
                // Drawn anew as a whole, the line follows the scan's tilt.
                rotation = (float) level.degrees();
            } else {
                size = round(fittedSize(doc, text, line.height(), line.width()));
                x = round(line.x());
                baseline = round(line.y() + line.height() / 2f - size * MIDDLE_ABOVE_BASELINE);
            }
            // Across the page the box keeps what the scan says and where each letter is: drawn, it changes only the
            // letters the user changed and leaves the others as scanned.
            AddedText.Cover cover = patch == null ? null
                    : new AddedText.Cover(round(crop.getLowerLeftX() + area.x0() / SCALE),
                            round(crop.getUpperRightY() - area.y1() / SCALE), round(area.width() / SCALE),
                            round(area.height() / SCALE), hex(paper), png(patch),
                            line.vertical() ? null : text,
                            line.vertical() ? null : letterPlaces(text, line, scanInk,
                                    crop.getLowerLeftX() + (area.x0() + coreInImage.x0()) / SCALE),
                            hex(scannedInk));
            return new AddedText(null, text, x, baseline, family, size, bold, hex(ink), List.of(), cover, rotation,
                    spacing);
        }
    }

    /**
     * A line's part of the page, drawn: the picture, where it is on the whole page (pixels), and where the line's
     * own box is in the picture (the picture reaches RINGING pixels further, less at the page's edge: a line's box
     * is drawn close round its letters, and their ringing reaches past it).
     */
    private record Region(BufferedImage image, Px area, Px core) {
    }

    /**
     * Only the line's part of the page is drawn: the whole page this sharp is hundreds of megabytes for a large
     * drawing.
     */
    private static Region region(PDFRenderer renderer, int pageIndex, PDRectangle crop, OcrText line)
            throws IOException {
        int pageWidth = Math.max(1, (int) Math.floor(crop.getWidth() * SCALE));
        int pageHeight = Math.max(1, (int) Math.floor(crop.getHeight() * SCALE));
        int x0 = clamp((int) Math.floor((line.x() - crop.getLowerLeftX()) * SCALE), 0, pageWidth - 1);
        int y0 = clamp((int) Math.floor((crop.getUpperRightY() - (line.y() + line.height())) * SCALE), 0,
                pageHeight - 1);
        Px core = new Px(x0, y0,
                clamp((int) Math.ceil((line.x() + line.width() - crop.getLowerLeftX()) * SCALE), x0 + 1, pageWidth),
                clamp((int) Math.ceil((crop.getUpperRightY() - line.y()) * SCALE), y0 + 1, pageHeight));
        Px area = new Px(Math.max(0, core.x0() - RINGING), Math.max(0, core.y0() - RINGING),
                Math.min(pageWidth, core.x1() + RINGING), Math.min(pageHeight, core.y1() + RINGING));
        BufferedImage image = new BufferedImage(area.width(), area.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, area.width(), area.height());
            g.translate(-area.x0(), -area.y0());
            renderer.renderPageToGraphics(pageIndex, g, SCALE);
        } finally {
            g.dispose();
        }
        return new Region(image, area, new Px(core.x0() - area.x0(), core.y0() - area.y0(), core.x1() - area.x0(),
                core.y1() - area.y0()));
    }

    /**
     * The look of the line: in the page's font (as rows of one table are set in one font, and one line of a coarse
     * scan tells fonts apart poorly), unless the line is clearly more like a face of the other style (명조 among
     * 고딕, or the other way round); bold when
     * its strokes are clearly heavier than the font's plain ones, or the page's are and the line's not clearly
     * lighter.
     *
     * @param heavy how much of the rectangle around the scan's letters their ink covers
     */
    private static ScanLook.Look chosenLook(String text, ScanLook.Ink ink, List<SystemFonts.Face> faces,
            PageFont page, double heavy, double[] middles) {
        Map<SystemFonts.Face, ScanLook.Look> looks = ScanLook.looks(text, ink, faces.stream().filter(f -> !f.bold())
                .toList(), middles);
        if (looks.isEmpty()) {
            return null;
        }
        Map.Entry<SystemFonts.Face, ScanLook.Look> own = looks.entrySet().stream()
                .max(java.util.Comparator.comparingDouble(e -> score(e.getValue(), heavy))).orElseThrow();
        Map.Entry<SystemFonts.Face, ScanLook.Look> chosen = own;
        if (page.family() != null) {
            chosen = looks.entrySet().stream().filter(e -> e.getKey().family().equals(page.family())).findFirst()
                    .filter(e -> serif(own.getKey()) == serif(e.getKey())
                            || score(own.getValue(), heavy) - score(e.getValue(), heavy) <= OTHER_FONT)
                    .orElse(own);
        }
        double weight = heavy / Math.max(1e-6, chosen.getValue().coverage());
        boolean bold = weight > BOLD_ALONE || (page.bold() && weight > BOLD_WITH_PAGE);
        if (!bold) {
            return chosen.getValue();
        }
        String family = chosen.getKey().family();
        return faces.stream().filter(f -> f.bold() && f.family().equals(family)).findFirst()
                .map(face -> ScanLook.looks(text, ink, List.of(face), middles).get(face))
                .orElse(chosen.getValue());
    }

    /**
     * How well a font's look fits the scan: its strokes falling on the scan's, made less the further its weight is
     * from the scan's (a heavy display face otherwise passes for a plain one on a coarse scan).
     *
     * @param heavy how much of the rectangle around the scan's letters their ink covers
     */
    private static double score(ScanLook.Look look, double heavy) {
        double weight = heavy / Math.max(1e-6, look.coverage());
        double misfit = Math.max(0, Math.abs(Math.log(Math.max(1e-6, weight))) - Math.log(WEIGHT_SLACK));
        return look.likeness() * Math.max(0.5, 1 - WEIGHT_MISFIT * misfit);
    }

    /**
     * The text with spaces put in front of each letter that comes after ink recognition did not read (a check box,
     * a symbol, which stays as scanned), so that the new letters, set in the look's font from the scan's first
     * letter on, leave it room as the scanned ones did.
     *
     * @param read    what recognition read off the line, and {@code centers} where each of its letters was seen
     * @param unread  where the unread ink lies (first and last pixel of the line's box)
     */
    static String withRoom(String text, String read, List<Float> centers, int width, List<int[]> unread,
            ScanLook.Ink ink, ScanLook.Look look) {
        if (centers == null || centers.size() != read.length()) {
            return text;
        }
        java.awt.Font font = ScanLook.awtFont(look.face()).map(f -> f.deriveFont((float) look.sizePx())).orElse(null);
        if (font == null) {
            return text;
        }
        List<Double> places = new ArrayList<>();
        for (int i = 0; i < read.length(); i++) {
            if (!Character.isWhitespace(read.charAt(i))) {
                places.add(centers.get(i) * (double) width);
            }
        }
        java.awt.font.FontRenderContext context = new java.awt.font.FontRenderContext(null, true, true);
        double space = font.getStringBounds(" ", context).getWidth();
        if (space <= 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text);
        List<int[]> spans = unread.stream().sorted(java.util.Comparator.comparingInt(s -> s[0])).toList();
        for (int[] span : spans) {
            // The first letter the scan has after the unread ink, and where it is in the text.
            int letter = 0;
            while (letter < places.size() && places.get(letter) <= span[1]) {
                letter++;
            }
            if (letter == 0 || letter >= places.size()) {
                continue; // before the first letter or after the last: nothing to make room in
            }
            int at = -1;
            for (int i = 0, seen = 0; i < out.length(); i++) {
                if (!Character.isWhitespace(out.charAt(i)) && seen++ == letter) {
                    at = i;
                    break;
                }
            }
            if (at < 0) {
                continue;
            }
            double left = ink.left() + font.getStringBounds(out.substring(0, at), context).getWidth();
            double need = span[1] + ROOM * look.sizePx() - left;
            if (need > 0) {
                out.insert(at, " ".repeat((int) Math.ceil(need / space)));
            }
        }
        return out.toString();
    }

    /**
     * For every char of {@code text} (the line's text, maybe spaced anew), from where to where its scanned letter
     * reaches along the page (PDF points); null for a space, null as a whole when the text does not have the
     * recognized letters. Recognition sees each letter a little early or late, the same along a line: its places are
     * stretched so that the first letter starts and the last ends where the scan's ink does. A letter is as wide as
     * the ink is tall for Hangul (and other square letters), about half that for others.
     *
     * @param ink       the line's own ink, in pixels of the picture whose pixel (0, 0) is page point
     *                  ({@code left}, top) at {@link #SCALE} pixels a point
     */
    static List<float[]> letterPlaces(String text, OcrText line, ScanLook.Ink ink, float left) {
        List<Character> letters = new ArrayList<>();
        List<Float> seen = new ArrayList<>();
        for (int i = 0; i < line.text().length(); i++) {
            if (!Character.isWhitespace(line.text().charAt(i))) {
                letters.add(line.text().charAt(i));
                seen.add(line.x() + line.letterPlaces()[i] * line.width());
            }
        }
        if (letters.isEmpty() || ink == null) {
            return null;
        }
        List<Double> middles = seen.stream().map(at -> (double) (at - left) * SCALE).toList();
        float height = (float) (ScanLook.letterHeight(ink, middles) / SCALE);
        float inkLeft = left + ink.left() / SCALE;
        float inkRight = left + (ink.right() + 1) / SCALE;
        float firstMiddle = inkLeft + halfWidth(letters.getFirst(), height);
        float lastMiddle = inkRight - halfWidth(letters.getLast(), height);
        float seenFirst = seen.getFirst();
        float seenLast = seen.getLast();
        List<float[]> scanned = new ArrayList<>();
        for (int k = 0; k < letters.size(); k++) {
            float middle = seenLast > seenFirst
                    ? firstMiddle + (seen.get(k) - seenFirst) / (seenLast - seenFirst) * (lastMiddle - firstMiddle)
                    : (firstMiddle + lastMiddle) / 2;
            float half = halfWidth(letters.get(k), height);
            scanned.add(new float[] {round(middle - half), round(middle + half)});
        }
        List<float[]> out = new ArrayList<>(text.length());
        int k = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                out.add(null);
            } else if (k < scanned.size()) {
                out.add(scanned.get(k++));
            } else {
                return null;
            }
        }
        return k == scanned.size() ? out : null;
    }

    /** Half a letter's width, for letters whose ink is {@code height} tall: square Hangul and Hanja, narrower others. */
    private static float halfWidth(char letter, float height) {
        Character.UnicodeScript script = Character.UnicodeScript.of(letter);
        boolean square = script == Character.UnicodeScript.HANGUL || script == Character.UnicodeScript.HAN;
        return height * (square ? 0.5f : 0.28f);
    }

    /** Whether the face is of the serif style (명조, 바탕), told by its name. */
    static boolean serif(SystemFonts.Face face) {
        String name = (face.family() + " " + face.label()).toLowerCase(java.util.Locale.ROOT);
        return name.contains("명조") || name.contains("바탕") || name.contains("myeongjo") || name.contains("batang")
                || name.contains("serif");
    }

    /** The size of the page's lines nearest {@code size} when one is within a few percent; else {@code size}. */
    static double pageSize(List<Double> sizes, double size) {
        List<Double> near = sizes.stream().filter(s -> Math.abs(s - size) <= SAME_SIZE * size).sorted().toList();
        return near.isEmpty() ? size : near.get(near.size() / 2);
    }

    /** The page's colour when {@code colour} is about the same, so lines of a page are not each a shade apart. */
    static Color alike(Color colour, Color page) {
        if (page == null) {
            return colour;
        }
        return Math.abs(lightness(colour.getRGB()) - lightness(page.getRGB())) / 1000 <= SAME_COLOUR ? page : colour;
    }

    /**
     * The font the page is set in, told by its longest lines read surely: the family that fits them best, on
     * average; bold when their strokes are clearly heavier than its plain ones; the sizes they are set at and the
     * colour they are redrawn in (the middle one). Kept for the page's next rewrites.
     */
    private PageFont pageFont(byte[] pdf, PDFRenderer renderer, int pageIndex, PDRectangle crop,
            List<OcrText> pageLines, List<SystemFonts.Face> faces) throws IOException {
        List<OcrText> samples = samples(pageLines);
        if (samples.isEmpty()) {
            return new PageFont(null, false, List.of(), null);
        }
        String key = pageKey(pdf, pageIndex, samples);
        CompletableFuture<PageFont> known;
        CompletableFuture<PageFont> mine = new CompletableFuture<>();
        synchronized (pageFonts) {
            known = pageFonts.putIfAbsent(key, mine);
        }
        if (known != null) {
            try {
                return known.join();
            } catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException failed) {
                return measuredPageFont(renderer, pageIndex, crop, samples, faces);
            }
        }
        try {
            PageFont found = measuredPageFont(renderer, pageIndex, crop, samples, faces);
            mine.complete(found);
            return found;
        } catch (IOException | RuntimeException failed) {
            synchronized (pageFonts) {
                pageFonts.remove(key, mine);
            }
            mine.completeExceptionally(failed);
            throw failed;
        }
    }

    /** The font of the page, told by {@code samples} of its lines. */
    private PageFont measuredPageFont(PDFRenderer renderer, int pageIndex, PDRectangle crop, List<OcrText> samples,
            List<SystemFonts.Face> faces) throws IOException {
        List<SystemFonts.Face> plain = faces.stream().filter(f -> !f.bold()).toList();
        Map<String, Double> fits = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        List<Map<SystemFonts.Face, ScanLook.Look>> sampleLooks = new ArrayList<>();
        List<double[]> sampleTone = new ArrayList<>();
        List<Color[]> sampleColours = new ArrayList<>();
        for (OcrText sample : samples) {
            Region region = region(renderer, pageIndex, crop, sample);
            Px core = region.core();
            Color paper = paper(pixels(region.image(), core));
            List<Integer> letterPixels = new ArrayList<>();
            boolean[][] solid = new boolean[core.height()][core.width()];
            patch(region.image(), core, new Px(0, 0, region.area().width(), region.area().height()), paper,
                    letterPixels, false, solid, sample.text(), sample.centers(), null);
            ScanLook.Ink scanned = ScanLook.Ink.of(solid);
            ScanLook.Ink ink = scanned == null ? null : ScanLook.Ink.of(Level.of(sample.tiltDegrees(), solid).solid());
            if (ink == null) {
                continue;
            }
            Color inkColour = ink(letterPixels, paper);
            double darkness = darkness(region.image(), core, scanned, paper);
            double heavy = heavy(darkness, paper, fullInk(letterPixels, paper));
            String text = ScanLook.spacedAsScanned(sample.text(), sample.centers(), ink, core.width());
            Map<SystemFonts.Face, ScanLook.Look> looks = ScanLook.looks(text, ink, plain,
                    ScanLook.letterMiddles(sample.text(), sample.centers(), core.width()));
            looks.forEach((face, look) -> {
                fits.merge(face.family(), score(look, heavy), Double::sum);
                counts.merge(face.family(), 1, Integer::sum);
            });
            sampleLooks.add(looks);
            sampleTone.add(new double[] {darkness, heavy});
            sampleColours.add(new Color[] {inkColour, paper});
        }
        int enough = (sampleLooks.size() + 1) / 2;
        // The best fit - or, among faces that fit about as well, the one most often met in documents.
        double best = fits.entrySet().stream().filter(e -> counts.get(e.getKey()) >= enough)
                .mapToDouble(e -> e.getValue() / counts.get(e.getKey())).max().orElse(0);
        String family = plain.stream().map(SystemFonts.Face::family).distinct()
                .filter(f -> fits.containsKey(f) && counts.get(f) >= enough && fits.get(f) / counts.get(f) >= best - TIE)
                .findFirst().orElse(null);
        double weight = 0;
        List<Double> sizes = new ArrayList<>();
        List<Color> colours = new ArrayList<>();
        for (int i = 0; i < sampleLooks.size(); i++) {
            for (Map.Entry<SystemFonts.Face, ScanLook.Look> e : sampleLooks.get(i).entrySet()) {
                if (e.getKey().family().equals(family)) {
                    ScanLook.Look look = e.getValue();
                    weight += sampleTone.get(i)[1] / Math.max(1e-6, look.coverage());
                    sizes.add(look.sizePx());
                    colours.add(toned(sampleColours.get(i)[0], sampleColours.get(i)[1], sampleTone.get(i)[0],
                            look.coverage()));
                }
            }
        }
        colours.sort(java.util.Comparator.comparingInt(c -> lightness(c.getRGB())));
        return new PageFont(family, !sizes.isEmpty() && weight / sizes.size() > BOLD_ALONE, sizes,
                colours.isEmpty() ? null : colours.get(colours.size() / 2));
    }

    /**
     * The ink at its darkest (the darkest tenth of the letters' pixels): what a stroke's core is, where a coarse
     * scan's blur has not lightened it.
     */
    static Color fullInk(List<Integer> pixels, Color paper) {
        List<Integer> ink = new ArrayList<>();
        for (int rgb : pixels) {
            if (difference(rgb, paper) > INK_DIFFERENCE) {
                ink.add(rgb);
            }
        }
        if (ink.isEmpty()) {
            return Color.BLACK;
        }
        ink.sort((a, b) -> Integer.compare(lightness(a), lightness(b)));
        return new Color(ink.get(ink.size() / 10));
    }

    /**
     * How much of the rectangle around the letters their ink would cover if it were all of {@code ink}: how dark
     * the letters are seen, for how dark their ink is.
     */
    private static double heavy(double darkness, Color paper, Color ink) {
        double paperLight = lightness(paper.getRGB()) / 1000.0;
        double inkDark = paperLight <= 0 ? 0 : (paperLight - lightness(ink.getRGB()) / 1000.0) / paperLight;
        return inkDark <= 0 ? 0 : darkness / inkDark;
    }

    /**
     * How dark the scan's letters are seen as a whole: over the rectangle around them, the mean of how much darker
     * than the paper each pixel is (0 paper, 1 black on white).
     */
    static double darkness(BufferedImage image, Px core, ScanLook.Ink ink, Color paper) {
        double paperLight = lightness(paper.getRGB()) / 1000.0;
        if (paperLight <= 0) {
            return 0;
        }
        double sum = 0;
        int n = 0;
        for (int y = ink.top(); y <= ink.bottom(); y++) {
            for (int x = ink.left(); x <= ink.right(); x++) {
                double light = lightness(image.getRGB(core.x0() + x, core.y0() + y)) / 1000.0;
                sum += Math.max(0, (paperLight - light) / paperLight);
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /**
     * The ink's colour moved towards the paper's until letters covering {@code coverage} of their rectangle are
     * as dark as {@code darkness}; never lighter than {@link #LIGHTEST_TONE} of the way.
     */
    static Color toned(Color ink, Color paper, double darkness, double coverage) {
        double paperLight = lightness(paper.getRGB()) / 1000.0;
        double inkDark = paperLight <= 0 ? 0 : (paperLight - lightness(ink.getRGB()) / 1000.0) / paperLight;
        if (inkDark <= 0 || coverage <= 0) {
            return ink;
        }
        double t = Math.max(LIGHTEST_TONE, Math.min(1, darkness / (coverage * inkDark)));
        return new Color((int) Math.round(paper.getRed() + t * (ink.getRed() - paper.getRed())),
                (int) Math.round(paper.getGreen() + t * (ink.getGreen() - paper.getGreen())),
                (int) Math.round(paper.getBlue() + t * (ink.getBlue() - paper.getBlue())));
    }

    /**
     * A line's ink levelled: each column moved up or down so that a line tilted {@code degrees} (counter-clockwise)
     * lies level, the rows grown by {@code pad} above and below to keep it all.
     *
     * @param slope how far the line falls (picture rows, down) per column across
     */
    record Level(double degrees, double slope, int pad, int middle, boolean[][] solid) {

        static Level of(double degrees, boolean[][] scanned) {
            int h = scanned.length;
            int w = h == 0 ? 0 : scanned[0].length;
            double slope = -Math.tan(Math.toRadians(degrees));
            if (degrees == 0 || w == 0) {
                return new Level(0, 0, 0, w / 2, scanned);
            }
            int middle = w / 2;
            int pad = (int) Math.ceil(Math.abs(slope) * middle) + 1;
            boolean[][] out = new boolean[h + 2 * pad][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (scanned[y][x]) {
                        out[y + pad - (int) Math.round(slope * (x - middle))][x] = true;
                    }
                }
            }
            return new Level(degrees, slope, pad, middle, out);
        }

        /** Where row {@code y} of the levelled ink, at column {@code x}, is on the line's picture. */
        double unlevelled(double x, double y) {
            return y - pad + slope * (x - middle);
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
     * edge of a shaded band - is not letters and is not painted over; nor is ink reaching the line box's edge
     * across the line (a rule slanting through a tilted scan, the row above or below) or, given the letters'
     * places, ink far from all of them (a symbol recognition did not read, such as a check box). Null when there
     * are no letters.
     *
     * @param letterPixels gets the colours of the letters' pixels
     * @param vertical     the line runs down the page: a rule along it is a column, one across it a row
     * @param solid        gets the letters' solid ink (not their soft edges), rows then columns of {@code core}
     * @param text         what was read off the line, or null
     * @param centers      where each of its letters was seen along the line (0 to 1), or null
     * @param unread       gets where the ink recognition did not read lies along the line (first and last pixel
     *                     of {@code core}), or null
     */
    static BufferedImage patch(BufferedImage image, Px core, Px area, Color paper, List<Integer> letterPixels,
            boolean vertical, boolean[][] solid, String text, List<Float> centers, List<int[]> unread) {
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

        boolean[][] letter = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                letter[y][x] = ink[y][x] && !ruleRow[y] && !ruleColumn[x];
            }
        }
        if (!vertical) {
            letter = withoutSlantingRules(letter);
        }
        letter = ownLetters(letter, vertical, text, centers, unread);

        boolean[][] paint = new boolean[area.height()][area.width()];
        boolean any = false;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!letter[y][x]) {
                    continue;
                }
                any = true;
                int rgb = image.getRGB(core.x0() + x, core.y0() + y);
                letterPixels.add(rgb & 0xFFFFFF);
                solid[y][x] = difference(rgb, paper) > SOLID_DIFFERENCE;
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
        // A compressed scan rings round its letters with pixels a little lighter or darker than the paper, further
        // out than their soft edges: left after the letters are painted over, they would outline them. Near the
        // letters, all that looks like paper (too light to be ink of its own) is painted too - leaving holes of
        // clean paper among the paint would have their edges show as specks when the picture is smoothed - a shade
        // less than opaque, so the patch still tells where the letters are.
        boolean[][] ringing = new boolean[area.height()][area.width()];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!letter[y][x]) {
                    continue;
                }
                for (int dy = -RINGING; dy <= RINGING; dy++) {
                    for (int dx = -RINGING; dx <= RINGING; dx++) {
                        int ax = core.x0() + x + dx - area.x0();
                        int ay = core.y0() + y + dy - area.y0();
                        if (ax < 0 || ay < 0 || ax >= area.width() || ay >= area.height()) {
                            continue;
                        }
                        if (!paint[ay][ax] && !ringing[ay][ax]) {
                            int d = difference(image.getRGB(area.x0() + ax, area.y0() + ay), paper);
                            ringing[ay][ax] = d <= LETTER_DIFFERENCE;
                        }
                    }
                }
            }
        }
        BufferedImage patch = new BufferedImage(area.width(), area.height(), BufferedImage.TYPE_INT_ARGB);
        int fill = 0xFF000000 | paper.getRGB() & 0xFFFFFF;
        int ringFill = RINGING_ALPHA << 24 | paper.getRGB() & 0xFFFFFF;
        // See-through pixels are the paper's colour too, not black: a viewer that smooths the picture blends its
        // colours before its transparency, and see-through black would show as a grey rim round the patch.
        int clear = paper.getRGB() & 0xFFFFFF;
        for (int ay = 0; ay < area.height(); ay++) {
            for (int ax = 0; ax < area.width(); ax++) {
                patch.setRGB(ax, ay, clear);
            }
        }
        for (int ay = 0; ay < area.height(); ay++) {
            for (int ax = 0; ax < area.width(); ax++) {
                if (paint[ay][ax] && !structure(ax + area.x0(), ay + area.y0(), core, ink, ruleRow, ruleColumn)) {
                    // Outside the line only what looks like paper is painted: ink there belongs to something else.
                    boolean inside = core.x0() <= ax + area.x0() && ax + area.x0() < core.x1()
                            && core.y0() <= ay + area.y0() && ay + area.y0() < core.y1();
                    if (inside || difference(image.getRGB(ax + area.x0(), ay + area.y0()), paper) <= LETTER_DIFFERENCE) {
                        patch.setRGB(ax, ay, fill);
                    }
                } else if (ringing[ay][ax]) {
                    patch.setRGB(ax, ay, ringFill);
                }
            }
        }
        feather(patch, image, area, paper);
        return patch;
    }

    /**
     * The patch's edge faded out over {@link #FEATHER} pixels of paper round it. A picture drawn at another size
     * than its own is smoothed, and smoothing an edge from opaque to clear in one pixel overshoots: viewers and the
     * preview alike would show it as a ring of specks lighter than the paper round the painted letters.
     */
    private static void feather(BufferedImage patch, BufferedImage image, Px area, Color paper) {
        int w = patch.getWidth();
        int h = patch.getHeight();
        int[] alpha = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                alpha[y * w + x] = patch.getRGB(x, y) >>> 24;
            }
        }
        int colour = paper.getRGB() & 0xFFFFFF;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (alpha[y * w + x] != 0
                        || difference(image.getRGB(x + area.x0(), y + area.y0()), paper) > LETTER_DIFFERENCE) {
                    continue;
                }
                int near = FEATHER + 1;
                for (int dy = -FEATHER; dy <= FEATHER; dy++) {
                    for (int dx = -FEATHER; dx <= FEATHER; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx >= 0 && ny >= 0 && nx < w && ny < h && alpha[ny * w + nx] > FEATHERED) {
                            near = Math.min(near, Math.max(Math.abs(dx), Math.abs(dy)));
                        }
                    }
                }
                if (near <= FEATHER) {
                    patch.setRGB(x, y, FEATHERED * (FEATHER + 1 - near) / FEATHER << 24 | colour);
                }
            }
        }
    }

    /**
     * The ink without the rules slanting along the line on a tilted scan. Row by row a slanting rule is a stretch
     * too short to be told a rule (it steps up or down a row every so often), but rows a few apart together hold it
     * unbroken over most of the line. A stretch of a row is taken for part of such a rule when it is longer than
     * any letter's stroke ({@link #RULE_PIECE} of the line) and the rows within {@link #SLANT_ROWS} of it hold ink
     * unbroken over {@link #RULE_ROW} of the line where it is; it is left out with the rows a couple above and below
     * it, its soft edges (the letters touching it are still painted over, a few pixels round their other ink). Left
     * in, the rule would be painted over with the letters and make the line look much taller than it is.
     */
    static boolean[][] withoutSlantingRules(boolean[][] ink) {
        int h = ink.length;
        int w = h == 0 ? 0 : ink[0].length;
        boolean[][] out = new boolean[h][];
        for (int y = 0; y < h; y++) {
            out[y] = ink[y].clone();
        }
        for (int y = 0; y < h; y++) {
            // Where the rows around this one hold ink, and the unbroken stretches of that.
            boolean[] band = new boolean[w];
            for (int yy = Math.max(0, y - SLANT_ROWS); yy <= Math.min(h - 1, y + SLANT_ROWS); yy++) {
                for (int x = 0; x < w; x++) {
                    band[x] |= ink[yy][x];
                }
            }
            for (int x = 0; x < w; ) {
                if (!ink[y][x]) {
                    x++;
                    continue;
                }
                int from = x;
                while (x < w && ink[y][x]) {
                    x++;
                }
                if (x - from < RULE_PIECE * w) {
                    continue;
                }
                int left = from;
                while (left > 0 && band[left - 1]) {
                    left--;
                }
                int right = x;
                while (right < w && band[right]) {
                    right++;
                }
                if (right - left >= RULE_ROW * w) {
                    // The rule and its soft edges, a couple of rows either side.
                    for (int yy = Math.max(0, y - SLANT_ROWS); yy <= Math.min(h - 1, y + SLANT_ROWS); yy++) {
                        for (int c = from; c < x; c++) {
                            out[yy][c] = false;
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * The line's own letters among the ink: patches of ink (touching corners counting as joined) that reach the
     * box's edge across the line - top or bottom, or the sides of a vertical line - are left out, and so, given
     * the letters' places, are patches whose middle is far from every letter's.
     */
    static boolean[][] ownLetters(boolean[][] ink, boolean vertical, String text, List<Float> centers,
            List<int[]> unread) {
        int h = ink.length;
        int w = h == 0 ? 0 : ink[0].length;
        double[] places = null;
        double pitch = 0;
        if (!vertical && text != null && centers != null && centers.size() == text.length()) {
            List<Double> letters = new ArrayList<>();
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isWhitespace(text.charAt(i))) {
                    letters.add(centers.get(i) * (double) w);
                }
            }
            if (letters.size() >= 2) {
                places = letters.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                double[] steps = new double[places.length - 1];
                for (int i = 0; i < steps.length; i++) {
                    steps[i] = places[i + 1] - places[i];
                }
                java.util.Arrays.sort(steps);
                pitch = steps[steps.length / 2];
            }
        }
        boolean[][] out = new boolean[h][w];
        boolean[][] seen = new boolean[h][w];
        int[] stack = new int[w * h];
        List<Integer> patch = new ArrayList<>();
        // The insides of the check boxes found so far: what lies in them (the mark of ▣) goes with the box.
        List<int[]> boxes = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!ink[y][x] || seen[y][x]) {
                    continue;
                }
                patch.clear();
                int top = 0;
                stack[top++] = y * w + x;
                seen[y][x] = true;
                int minX = x;
                int maxX = x;
                int minY = y;
                int maxY = y;
                while (top > 0) {
                    int at = stack[--top];
                    patch.add(at);
                    int py = at / w;
                    int px = at % w;
                    minX = Math.min(minX, px);
                    maxX = Math.max(maxX, px);
                    minY = Math.min(minY, py);
                    maxY = Math.max(maxY, py);
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int ny = py + dy;
                            int nx = px + dx;
                            if (ny >= 0 && ny < h && nx >= 0 && nx < w && ink[ny][nx] && !seen[ny][nx]) {
                                seen[ny][nx] = true;
                                stack[top++] = ny * w + nx;
                            }
                        }
                    }
                }
                int across = vertical ? w : h;
                int low = vertical ? minX : minY;
                int high = vertical ? maxX : maxY;
                // Reaching the box's edge and less than half as tall as the box: a rule slanting past, a piece of the
                // row above or below. (A letter's tall stroke can reach the edge too, but it is the letter's height.)
                boolean atEdge = (low <= EDGE || high >= across - 1 - EDGE) && high - low + 1 < EDGE_SPAN * across;
                // A thin patch lying all in the box's outer band across the line: a rule slanting past, not a letter.
                boolean thin = high - low + 1 <= THIN * across;
                atEdge |= thin && (high < OUTER * across || low > (1 - OUTER) * across);
                boolean inBox = false;
                for (int[] inside : boxes) {
                    inBox |= minX >= inside[0] && maxX <= inside[2] && minY >= inside[1] && maxY <= inside[3];
                }
                if (inBox) {
                    continue;
                }
                if (!vertical && !atEdge && checkBox(patch, w, minX, minY, maxX, maxY, h)) {
                    boxes.add(new int[] {minX, minY, maxX, maxY});
                    if (unread != null) {
                        unread.add(new int[] {minX, maxX});
                    }
                    continue;
                }
                boolean far = false;
                if (places != null) {
                    double middle = (minX + maxX) / 2.0;
                    double nearest = Double.MAX_VALUE;
                    for (double place : places) {
                        nearest = Math.min(nearest, Math.abs(place - middle));
                    }
                    far = nearest > UNREAD * pitch;
                }
                if (!atEdge && !far) {
                    for (int at : patch) {
                        out[at / w][at % w] = true;
                    }
                } else if (far && !atEdge && unread != null) {
                    unread.add(new int[] {minX, maxX});
                }
            }
        }
        return out;
    }

    /**
     * Whether a patch of ink is a check box (□, or the frame of ▣): about square, about a letter tall, and drawn
     * round its edge alone - ink on most of each side, none in its middle. Recognition does not read these, and
     * they stay as scanned even when the whole line is drawn anew; next to a letter they are too close to be told
     * apart by where the letters are.
     *
     * @param across the line's box height, pixels
     */
    static boolean checkBox(List<Integer> patch, int w, int minX, int minY, int maxX, int maxY, int across) {
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        if (height < BOX_SIDE_LEAST * across || height > across || width < BOX_SQUARE * height
                || height < BOX_SQUARE * width) {
            return false;
        }
        int bandX = Math.max(1, (int) Math.round(width * BOX_BAND));
        int bandY = Math.max(1, (int) Math.round(height * BOX_BAND));
        boolean[] top = new boolean[width];
        boolean[] bottom = new boolean[width];
        boolean[] left = new boolean[height];
        boolean[] right = new boolean[height];
        int middle = 0;
        for (int at : patch) {
            int x = at % w - minX;
            int y = at / w - minY;
            top[x] |= y < bandY;
            bottom[x] |= y >= height - bandY;
            left[y] |= x < bandX;
            right[y] |= x >= width - bandX;
            if (x >= width / 4 && x < width - width / 4 && y >= height / 4 && y < height - height / 4) {
                middle++;
            }
        }
        return middle <= BOX_MIDDLE * patch.size() && share(top) >= BOX_SIDES && share(bottom) >= BOX_SIDES
                && share(left) >= BOX_SIDES && share(right) >= BOX_SIDES;
    }

    private static double share(boolean[] marks) {
        int n = 0;
        for (boolean mark : marks) {
            n += mark ? 1 : 0;
        }
        return marks.length == 0 ? 0 : n / (double) marks.length;
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
     * The paper's colour: around the commonest colour (once colours a little apart are taken as one, or the one
     * exact colour most of them have, for paper drawn as a flat fill), the average of the pixels close to it. A
     * scan's paper is that colour strewn with lighter and darker specks, which a viewer averages when it shows the
     * page smaller: painted in the commonest colour alone, a patch stands out lighter or darker than the paper.
     */
    static Color paper(List<Integer> pixels) {
        Color base = commonestPaper(pixels);
        long r = 0;
        long g = 0;
        long b = 0;
        int n = 0;
        for (int rgb : pixels) {
            if (difference(rgb, base) <= PAPER_SPECKS) {
                r += rgb >> 16 & 0xFF;
                g += rgb >> 8 & 0xFF;
                b += rgb & 0xFF;
                n++;
            }
        }
        return n == 0 ? base
                : new Color((int) Math.round(r / (double) n), (int) Math.round(g / (double) n),
                        (int) Math.round(b / (double) n));
    }

    /**
     * The commonest colour, once colours a little apart are taken as one, averaged over the pixels that have it;
     * when one exact colour makes up most of them (paper drawn as a flat fill), that colour.
     */
    private static Color commonestPaper(List<Integer> pixels) {
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
     * The ink's colour: the one a quarter of the way (by lightness) from the darkest of the pixels that clearly differ
     * from the paper - the soft edges of the letters on one side, their darkest cores on the other; the eye sees a
     * scanned stroke by its core more than its edges, and the edges of small letters are most of their pixels.
     * Black when there is no ink to see.
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
        ink.sort((a, b) -> Integer.compare(lightness(a), lightness(b)));
        return new Color(ink.get(ink.size() / 4));
    }

    private static int lightness(int rgb) {
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
