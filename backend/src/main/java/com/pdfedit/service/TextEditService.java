package com.pdfedit.service;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pdfedit.dto.PageTextDto;
import com.pdfedit.dto.TextEditItem;
import com.pdfedit.dto.TextRunDto;
import com.pdfedit.text.AddedText;
import com.pdfedit.text.FontMatcher;
import com.pdfedit.text.Glyph;
import com.pdfedit.text.RunEdit;
import com.pdfedit.text.RunPainter;
import com.pdfedit.text.TextExtraction;
import com.pdfedit.text.TextRun;
import com.pdfedit.text.VerticalBounds;

/**
 * Text editing on top of PDFBox: lists a page's editable runs, records what the user did to them
 * (new text, another font/size/weight/colour, a move) and the text boxes they added, and bakes it
 * all into a page. An edited run is a cover-up rectangle in the colour behind it followed by the
 * new text, redrawn with the run's own original embedded font when possible (see
 * {@link FontMatcher#tryLoadOriginalEmbedded}), falling back to an installed substitute. The
 * original text stays in the content stream underneath, so this is not a redaction tool.
 */
@Service
public class TextEditService {

    private static final Logger log = LoggerFactory.getLogger(TextEditService.class);
    private static final int PREVIEW_DPI = 150;
    /** Resolution of the pictures the cover-ups are made from (3x = 216 dpi: rules stay crisp in a patch). */
    private static final float BACKDROP_SCALE = 3f;
    private static final float MIN_FONT_SIZE = 1f;
    private static final float MAX_FONT_SIZE = 500f;
    private static final float MAX_OFFSET = 10_000f;
    private static final int MAX_ADDED_BOXES = 200;
    private static final int MAX_ADDED_LENGTH = 5_000;
    private static final float LINE_SPACING = 1.2f;
    private static final String COLOUR_PATTERN = "#[0-9a-fA-F]{6}";

    private final FontMatcher fontMatcher;

    public TextEditService(FontMatcher fontMatcher) {
        this.fontMatcher = fontMatcher;
    }

    /** Fonts embedded into one output document, so each is embedded once however many edits use it. */
    public static final class FontCache {
        private final Map<String, PDFont> primary = new HashMap<>();
        private final Map<String, PDFont> fallback = new HashMap<>();
        private final Map<String, FontMatcher.Styled> styled = new HashMap<>();
        private final RunPainter painter;

        private FontCache(FontMatcher fontMatcher) {
            this.painter = new RunPainter(fontMatcher);
        }
    }

    /** A cache for one output document; share it between all the pages exported into that document. */
    public FontCache newFontCache() {
        return new FontCache(fontMatcher);
    }

    public PageTextDto describePage(byte[] pdf, int pageIndex, Map<Integer, RunEdit> saved) throws IOException {
        return describePage(pdf, pageIndex, PageEdits.ofRuns(saved));
    }

    public PageTextDto describePage(byte[] pdf, int pageIndex, PageEdits saved) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage page = doc.getPage(pageIndex);
            PDRectangle crop = page.getCropBox();
            boolean rotated = isRotated(page);
            List<TextRunDto> dtos = new ArrayList<>();
            if (!rotated) {
                List<TextRun> runs = TextExtraction.extractRuns(doc, pageIndex);
                RunPainter.PageGlyphs pageGlyphs = RunPainter.PageGlyphs.of(runs);
                Map<COSDictionary, FontMatcher.Match> matchByFont = new IdentityHashMap<>();
                for (int i = 0; i < runs.size(); i++) {
                    TextRun run = runs.get(i);
                    RunEdit existing = saved.runs().get(i);
                    FontMatcher.Match match = run.sourceFont() == null
                            ? FontMatcher.guess(run.sourceFontName(), null)
                            : matchByFont.computeIfAbsent(run.sourceFont().getCOSObject(),
                                    k -> fontMatcher.matchFor(run.sourceFont(), pageGlyphs));
                    float[] bounds = VerticalBounds.compute(runs, run);
                    dtos.add(new TextRunDto(i, run.text(), existing != null ? existing.text() : run.text(),
                            existing != null, run.x(), run.y(), run.width(), run.height(), run.fontSize(),
                            bounds[0], bounds[1], match.cssFamily, match.bold,
                            sourceFontLabel(run), RunEdit.hex(runColour(run)), RunPainter.canReuseGlyphs(run),
                            existing == null ? null : existing.fontFamily(),
                            existing == null ? null : existing.fontSize(),
                            existing == null ? null : existing.bold(),
                            existing == null ? null : existing.color(),
                            existing == null ? 0f : existing.dx(), existing == null ? 0f : existing.dy()));
                }
            }
            return new PageTextDto(crop.getWidth(), crop.getHeight(), crop.getLowerLeftX(),
                    crop.getLowerLeftY(), rotated, dtos, saved.added());
        }
    }

    /**
     * Merges the submitted states into {@code pageEdits}; a run sent back as its original text with
     * nothing changed and nowhere moved clears its edit.
     */
    public void recordEdits(byte[] pdf, int pageIndex, Map<Integer, RunEdit> pageEdits, List<TextEditItem> items)
            throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            if (isRotated(doc.getPage(pageIndex))) {
                throw new PageNotEditableException("회전된 페이지는 텍스트 편집을 지원하지 않습니다.");
            }
            List<TextRun> runs = TextExtraction.extractRuns(doc, pageIndex);
            Set<String> checkedFonts = new HashSet<>();
            for (TextEditItem item : items) {
                if (item.index() < 0 || item.index() >= runs.size()) {
                    continue;
                }
                TextRun original = runs.get(item.index());
                RunEdit edit = toEdit(item);
                if (edit.text().equals(original.text()) && !edit.restyled() && !edit.moved()) {
                    pageEdits.remove(item.index());
                    continue;
                }
                // Refuse now, with a readable message, rather than failing later when the
                // preview or export cannot find a font to redraw the text with.
                if (!edit.text().isEmpty()) {
                    checkFont(original, edit, checkedFonts);
                }
                pageEdits.put(item.index(), edit);
            }
        }
    }

    private void checkFont(TextRun original, RunEdit edit, Set<String> checkedFonts) {
        if (edit.fontFamily() != null) {
            boolean bold = edit.bold() != null ? edit.bold()
                    : FontMatcher.guess(original.sourceFontName(), original.sourceFont()).bold;
            fontMatcher.checkCovers(edit.fontFamily(), bold, edit.text());
        } else if (edit.bold() != null) {
            fontMatcher.checkCovers(FontMatcher.guess(original.sourceFontName(), original.sourceFont()).cssFamily,
                    edit.bold(), edit.text());
        } else if (checkedFonts.add(String.valueOf(original.sourceFontName()))) {
            fontMatcher.checkAvailable(original.sourceFontName(), original.sourceFont());
        }
    }

    private static RunEdit toEdit(TextEditItem item) {
        String text = item.text() == null ? "" : item.text();
        Float size = item.fontSize();
        if (size != null && !(size >= MIN_FONT_SIZE && size <= MAX_FONT_SIZE)) {
            throw new InvalidEditException("글자 크기는 " + (int) MIN_FONT_SIZE + "~" + (int) MAX_FONT_SIZE + "pt 사이여야 합니다.");
        }
        String color = item.color() == null || item.color().isBlank() ? null : item.color();
        if (color != null && !color.matches(COLOUR_PATTERN)) {
            throw new InvalidEditException("글자 색은 #rrggbb 형식이어야 합니다.");
        }
        float dx = item.dx() == null ? 0f : item.dx();
        float dy = item.dy() == null ? 0f : item.dy();
        if (!(Math.abs(dx) <= MAX_OFFSET && Math.abs(dy) <= MAX_OFFSET)) {
            throw new InvalidEditException("이동 거리가 너무 큽니다.");
        }
        String family = item.fontFamily() == null || item.fontFamily().isBlank() ? null : item.fontFamily();
        return new RunEdit(text, family, size, item.bold(), color, dx, dy);
    }

    /**
     * Checks the text boxes of a page before they are stored: sizes, colours, positions, and that
     * each font has every character typed into it (otherwise the text would silently not appear).
     *
     * @return the boxes, unchanged, ready to store
     */
    public List<AddedText> checkAdded(byte[] pdf, int pageIndex, List<AddedText> boxes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            if (isRotated(doc.getPage(pageIndex))) {
                throw new PageNotEditableException("회전된 페이지에는 텍스트를 추가할 수 없습니다.");
            }
        }
        if (boxes.size() > MAX_ADDED_BOXES) {
            throw new InvalidEditException("한 페이지에는 텍스트 상자를 " + MAX_ADDED_BOXES + "개까지 추가할 수 있습니다.");
        }
        for (AddedText box : boxes) {
            if (!(box.fontSize() >= MIN_FONT_SIZE && box.fontSize() <= MAX_FONT_SIZE)) {
                throw new InvalidEditException("글자 크기는 " + (int) MIN_FONT_SIZE + "~" + (int) MAX_FONT_SIZE + "pt 사이여야 합니다.");
            }
            if (box.color() != null && !box.color().matches(COLOUR_PATTERN)) {
                throw new InvalidEditException("글자 색은 #rrggbb 형식이어야 합니다.");
            }
            if (!(Math.abs(box.x()) <= MAX_OFFSET && Math.abs(box.y()) <= MAX_OFFSET)) {
                throw new InvalidEditException("텍스트 상자 위치가 페이지 밖입니다.");
            }
            String text = box.text() == null ? "" : box.text();
            if (text.length() > MAX_ADDED_LENGTH) {
                throw new InvalidEditException("텍스트 상자 하나에는 " + MAX_ADDED_LENGTH + "자까지 쓸 수 있습니다.");
            }
            if (!text.isBlank()) {
                fontMatcher.checkCovers(box.fontFamily(), box.bold(), text);
            }
        }
        return boxes;
    }

    /** A one-page PDF of {@code pageIndex} with its edits baked in (for previews). */
    public byte[] buildSinglePage(byte[] pdf, int pageIndex, Map<Integer, RunEdit> edits) throws IOException {
        return buildSinglePage(pdf, pageIndex, PageEdits.ofRuns(edits));
    }

    public byte[] buildSinglePage(byte[] pdf, int pageIndex, PageEdits edits) throws IOException {
        try (PDDocument src = Loader.loadPDF(pdf);
                PDDocument out = new PDDocument()) {
            PDPage imported = out.importPage(src.getPage(pageIndex));
            if (!edits.isEmpty()) {
                applyEdits(src, pdf, pageIndex, out, imported, edits, newFontCache(), "");
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            out.save(bos);
            return bos.toByteArray();
        }
    }

    /** PNG of the page at {@value #PREVIEW_DPI} DPI with its edits baked in. */
    public byte[] renderPreview(byte[] pdf, int pageIndex, Map<Integer, RunEdit> edits) throws IOException {
        return renderPreview(pdf, pageIndex, PageEdits.ofRuns(edits));
    }

    public byte[] renderPreview(byte[] pdf, int pageIndex, PageEdits edits) throws IOException {
        return renderPreview(pdf, pageIndex, edits, PREVIEW_DPI);
    }

    /** The preview at another resolution: a zoomed-in editor asks for more pixels so the page stays sharp. */
    public byte[] renderPreview(byte[] pdf, int pageIndex, PageEdits edits, int dpi) throws IOException {
        byte[] singlePage = edits.isEmpty() ? null : buildSinglePage(pdf, pageIndex, edits);
        try (PDDocument doc = Loader.loadPDF(singlePage != null ? singlePage : pdf)) {
            int renderIndex = singlePage != null ? 0 : pageIndex;
            BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(renderIndex, dpi);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bos);
            return bos.toByteArray();
        }
    }

    /**
     * Applies {@code edits} of {@code srcDoc}'s page to {@code outPage}, which must already be a page
     * of {@code outDoc} imported from that source page.
     *
     * @param fontScope distinguishes source documents sharing one {@link FontCache}: the same font
     *                  name in two documents can be two different embedded fonts
     */
    public void applyEdits(PDDocument srcDoc, byte[] srcPdf, int pageIndex, PDDocument outDoc, PDPage outPage,
            PageEdits edits, FontCache fonts, String fontScope) throws IOException {
        List<TextRun> allRuns = TextExtraction.extractRuns(srcDoc, pageIndex);
        RunPainter.PageGlyphs pageGlyphs = RunPainter.PageGlyphs.of(allRuns);
        PDRectangle crop = srcDoc.getPage(pageIndex).getCropBox();
        // The page as it looks before any edit, and the same page without its text: comparing the two
        // shows where the letters' ink is, and the second is what covers it - so the cover-up matches a
        // coloured table cell, and leaves the table rules next to the text alone.
        BufferedImage backdrop = edits.runs().isEmpty() ? null
                : new PDFRenderer(srcDoc).renderImage(pageIndex, BACKDROP_SCALE);
        BufferedImage clean = backdrop == null ? null : textFree(srcPdf, pageIndex);
        try (PDPageContentStream cs = new PDPageContentStream(outDoc, outPage,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            for (Map.Entry<Integer, RunEdit> entry : edits.runs().entrySet()) {
                int runIndex = entry.getKey();
                if (runIndex < 0 || runIndex >= allRuns.size()) {
                    continue;
                }
                applyRunEdit(cs, outDoc, allRuns, allRuns.get(runIndex), entry.getValue(), pageGlyphs, backdrop,
                        clean, crop, fonts, fontScope);
            }
            for (AddedText box : edits.added()) {
                drawAdded(cs, outDoc, box, fonts);
            }
        }
    }

    private void applyRunEdit(PDPageContentStream cs, PDDocument outDoc, List<TextRun> allRuns, TextRun original,
            RunEdit edit, RunPainter.PageGlyphs pageGlyphs, BufferedImage backdrop, BufferedImage clean,
            PDRectangle crop, FontCache fonts, String fontScope) throws IOException {
        // The white-out box is driven by fontSize, not the extracted glyph height: PDFBox's
        // per-glyph height proved unreliable across embedded fonts (e.g. ~5.97pt for text
        // rendered at ~8.3pt), leaving tall glyphs poking out above too-short boxes.
        float[] bounds = VerticalBounds.compute(allRuns, original);
        float boxBottom = bounds[0];
        float boxTop = bounds[1];

        String text = edit.text() == null ? "" : edit.text();
        boolean hasText = !text.isEmpty();
        float size = edit.fontSize() != null ? edit.fontSize() : original.fontSize();
        Color colour = RunEdit.colour(edit.color(), runColour(original));
        float boxWidth = original.width();

        // Preferred: redraw character by character so unchanged text keeps its exact glyphs,
        // fonts, colours and spacing. Falls back to one substitute font for the whole run when
        // the source characters can't be reproduced individually - or when the user chose another
        // font, size, weight or colour, which a glyph-for-glyph copy of the original can't honour.
        RunPainter.Layout layout = null;
        if (hasText && !edit.restyled()) {
            layout = fonts.painter.layout(outDoc, original, text, pageGlyphs, fontScope).orElse(null);
            if (layout != null) {
                boxWidth = Math.max(original.width(), layout.rightEdge() - original.x());
            }
        }

        FontMatcher.Styled styled = null;
        if (hasText && layout == null) {
            styled = fontFor(outDoc, original, edit, text, size, pageGlyphs, fonts, fontScope);
            Float measured = tryMeasureWidth(styled.font(), text, size);
            // The new text may render wider than the original run; a white-out only as wide
            // as the original would expose original text under the spilled tail.
            boxWidth = Math.max(original.width(), measured != null ? measured : text.length() * size * 0.6f);
        }

        // A moved line leaves its old place behind: only that place is covered, the line is
        // drawn again where it now is.
        float coverX = original.x() - 1;
        float coverWidth = (edit.moved() ? original.width() : boxWidth) + 2;
        if (clean != null) {
            TextCover.paint(cs, outDoc, backdrop, clean, crop, BACKDROP_SCALE, coverX, boxBottom, coverWidth,
                    boxTop - boxBottom);
        } else {
            // The page could not be drawn without its text: a flat box in the most common colour behind it.
            cs.setNonStrokingColor(backgroundBehind(backdrop, crop, coverX, boxBottom, coverWidth, boxTop - boxBottom));
            cs.addRect(coverX, boxBottom, coverWidth, boxTop - boxBottom);
            cs.fill();
        }

        if (layout != null) {
            layout.draw(cs, original.y() + edit.dy(), edit.dx());
        } else if (hasText) {
            drawLine(cs, styled, text, original.x() + edit.dx(), original.y() + edit.dy(), size, colour);
        }
    }

    /** The font an edited run is redrawn in: the user's pick, else the run's own font or its nearest installed one. */
    private FontMatcher.Styled fontFor(PDDocument outDoc, TextRun original, RunEdit edit, String text, float size,
            RunPainter.PageGlyphs pageGlyphs, FontCache fonts, String fontScope) {
        if (edit.fontFamily() != null || edit.bold() != null) {
            FontMatcher.Match match = original.sourceFont() == null
                    ? FontMatcher.guess(original.sourceFontName(), null)
                    : fontMatcher.matchFor(original.sourceFont(), pageGlyphs);
            boolean bold = edit.bold() != null ? edit.bold() : match.bold;
            String key = (edit.fontFamily() != null ? "family:" + edit.fontFamily() : "match:" + match.cssFamily)
                    + "|" + bold;
            FontMatcher.Styled picked = fonts.styled.computeIfAbsent(key, k -> edit.fontFamily() != null
                    ? fontMatcher.loadStyled(outDoc, edit.fontFamily(), bold)
                    : fontMatcher.loadMatch(outDoc, match, bold));
            if (tryMeasureWidth(picked.font(), text, size) != null) {
                return picked;
            }
            // The chosen font cannot draw a character after all; keep the text rather than drop it.
        }
        String key = fontScope + "|" + (original.sourceFontName() != null ? original.sourceFontName() : "__default__");
        PDFont originalSourceFont = original.sourceFont();
        PDFont font = fonts.primary.computeIfAbsent(key, k -> {
            PDFont embedded = originalSourceFont != null
                    ? FontMatcher.tryLoadOriginalEmbedded(outDoc, originalSourceFont) : null;
            return embedded != null ? embedded
                    : fontMatcher.loadForExport(outDoc, original.sourceFontName(), originalSourceFont);
        });
        if (tryMeasureWidth(font, text, size) == null) {
            // The cached font - often the run's own original subset, limited to whatever
            // characters the source already used - can't encode this text. Fall back to
            // the full-coverage substitute for just this edit instead of dropping it.
            font = fonts.fallback.computeIfAbsent(key,
                    k -> fontMatcher.loadForExport(outDoc, original.sourceFontName(), originalSourceFont));
        }
        return new FontMatcher.Styled(font, false);
    }

    private void drawAdded(PDPageContentStream cs, PDDocument outDoc, AddedText box, FontCache fonts)
            throws IOException {
        if (box.text() == null || box.text().isBlank()) {
            return;
        }
        String key = "family:" + (box.fontFamily() == null ? "" : box.fontFamily()) + "|" + box.bold();
        FontMatcher.Styled styled = fonts.styled.computeIfAbsent(key,
                k -> fontMatcher.loadStyled(outDoc, box.fontFamily(), box.bold()));
        Color colour = RunEdit.colour(box.color(), Color.BLACK);
        String[] lines = box.text().split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                drawLine(cs, styled, lines[i], box.x(), box.y() - i * box.fontSize() * LINE_SPACING,
                        box.fontSize(), colour);
            }
        }
    }

    /** The page drawn without its text, or null when that is not possible (the cover then falls back to a flat box). */
    private static BufferedImage textFree(byte[] pdf, int pageIndex) {
        try {
            return TextFreeRenderer.render(pdf, pageIndex, BACKDROP_SCALE);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not draw page {} without its text, covering edits with a flat box: {}", pageIndex, e.toString());
            return null;
        }
    }

    /**
     * The colour behind a box on the page: the most common colour in that area as the page draws it.
     * The text itself is a minority of the pixels, so what is left is the fill of the table cell or
     * paper the text sits on.
     */
    static Color backgroundBehind(BufferedImage page, PDRectangle crop, float x, float bottom, float width,
            float height) {
        int x0 = clamp((int) Math.floor((x - crop.getLowerLeftX()) * BACKDROP_SCALE), 0, page.getWidth() - 1);
        int x1 = clamp((int) Math.ceil((x + width - crop.getLowerLeftX()) * BACKDROP_SCALE), x0 + 1, page.getWidth());
        int y0 = clamp((int) Math.floor((crop.getHeight() - (bottom + height - crop.getLowerLeftY())) * BACKDROP_SCALE),
                0, page.getHeight() - 1);
        int y1 = clamp((int) Math.ceil((crop.getHeight() - (bottom - crop.getLowerLeftY())) * BACKDROP_SCALE),
                y0 + 1, page.getHeight());

        // A fill is drawn in exactly one colour, so the exact colour that occurs most often is the
        // background; averaging near colours would turn pure white into 254 and show as a faint patch.
        Map<Integer, Integer> counts = new HashMap<>();
        int bestRgb = 0xFFFFFF;
        int bestCount = 0;
        for (int y = y0; y < y1; y++) {
            for (int px = x0; px < x1; px++) {
                int rgb = page.getRGB(px, y) & 0xFFFFFF;
                int count = counts.merge(rgb, 1, Integer::sum);
                if (count > bestCount) {
                    bestCount = count;
                    bestRgb = rgb;
                }
            }
        }
        return new Color(bestRgb);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isRotated(PDPage page) {
        return page.getRotation() % 360 != 0;
    }

    /** The colour the run was printed in (its first character's fill), black when it can't be told. */
    private static Color runColour(TextRun run) {
        for (Glyph glyph : run.glyphs()) {
            if (glyph.fill() != null) {
                try {
                    return new Color(glyph.fill().toRGB());
                } catch (IOException | RuntimeException unreadable) {
                    break;
                }
            }
        }
        return Color.BLACK;
    }

    /** How the PDF names the run's font, without the random subset tag ("ABCDEF+") fonts are embedded under. */
    private static String sourceFontLabel(TextRun run) {
        String name = run.sourceFontName();
        if (run.sourceFont() instanceof PDType3Font) {
            return "Type3 글꼴" + (name == null ? "" : " (" + name + ")");
        }
        if (name == null) {
            return "알 수 없음";
        }
        return name.replaceFirst("^[A-Z]{6}\\+", "");
    }

    /** Width in points the font would render the text at, or null if any glyph is unsupported. */
    private static Float tryMeasureWidth(PDFont font, String text, float fontSize) {
        try {
            return font.getStringWidth(text) / 1000f * fontSize;
        } catch (Exception e) {
            return null;
        }
    }

    private static void drawLine(PDPageContentStream cs, FontMatcher.Styled styled, String text, float x, float y,
            float fontSize, Color colour) throws IOException {
        cs.saveGraphicsState();
        try {
            cs.setNonStrokingColor(colour);
            if (styled.fauxBold()) {
                // A family without a bold cut: thicken the strokes a little instead.
                cs.setStrokingColor(colour);
                cs.setRenderingMode(RenderingMode.FILL_STROKE);
                cs.setLineWidth(fontSize * 0.03f);
            }
            // beginText()/endText() must pair up even when showText() throws mid-block, or the next
            // edit's beginText() fails with "Nested beginText() calls are not allowed".
            cs.beginText();
            try {
                cs.setFont(styled.font(), fontSize);
                cs.newLineAtOffset(x, y);
                cs.showText(text);
            } catch (IllegalArgumentException glyphError) {
                // Character not supported by this font; the cover-up above still applied.
            } finally {
                cs.endText();
            }
        } finally {
            cs.restoreGraphicsState();
        }
    }
}
