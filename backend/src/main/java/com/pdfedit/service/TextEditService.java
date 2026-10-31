package com.pdfedit.service;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
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
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pdfedit.dto.PageTextDto;
import com.pdfedit.dto.TextEditItem;
import com.pdfedit.dto.TextRunDto;
import com.pdfedit.text.AddedImage;
import com.pdfedit.text.AddedText;
import com.pdfedit.text.FontMatcher;
import com.pdfedit.text.FontUnavailableException;
import com.pdfedit.text.Glyph;
import com.pdfedit.text.PageExtraction;
import com.pdfedit.text.RunEdit;
import com.pdfedit.text.RunPainter;
import com.pdfedit.text.TextExtraction;
import com.pdfedit.text.TextLines;
import com.pdfedit.text.TextRun;
import com.pdfedit.text.VerticalBounds;

/**
 * Text editing on top of PDFBox: lists a page's editable runs, records what the user did to them
 * (new text, another font/size/weight/colour, a move) and the text boxes they added, and bakes it
 * all into a page. An edited run's original text is cut out of the page's content when that can be
 * proven harmless (see {@link TextRemoval}); otherwise a cover-up rectangle in the colour behind it
 * hides it and the original text stays in the content stream underneath. The new text is redrawn with
 * the run's own glyphs when possible, falling back to an installed substitute font.
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
    private static final int MAX_SPANS = 200;
    private static final int MAX_ADDED_LENGTH = 5_000;
    private static final float LINE_SPACING = 1.2f;
    private static final String COLOUR_PATTERN = "#[0-9a-fA-F]{6}";
    /** Limits of the patch over a rewritten scanned line: a long line at 3 pixels per point is far smaller. */
    private static final int MAX_PATCH_CHARS = 2_000_000;
    private static final long MAX_PATCH_PIXELS = 8_000_000L;

    private final FontMatcher fontMatcher;

    public TextEditService(FontMatcher fontMatcher) {
        this.fontMatcher = fontMatcher;
    }

    /** Fonts embedded into one output document, so each is embedded once however many edits use it. */
    public static final class FontCache {
        private final Map<String, PDFont> primary = new HashMap<>();
        private final Map<String, PDFont> fallback = new HashMap<>();
        private final Map<String, FontMatcher.Styled> styled = new HashMap<>();
        /** Pictures embedded into the output document, by picture and how they are drawn. */
        private final Map<String, PDImageXObject> images = new HashMap<>();
        private final RunPainter painter;

        private FontCache(FontMatcher fontMatcher) {
            this.painter = new RunPainter(fontMatcher);
        }
    }

    /** A cache for one output document; share it between all the pages exported into that document. */
    public FontCache newFontCache() {
        return new FontCache(fontMatcher);
    }

    /**
     * The default Korean font (the one added text boxes use), embedded once into the output document; null when
     * this PC has no Korean font at all.
     */
    public PDFont defaultFont(FontCache fonts, PDDocument outDoc) {
        try {
            return fonts.styled.computeIfAbsent("family:|false", k -> fontMatcher.loadStyled(outDoc, null, false))
                    .font();
        } catch (FontUnavailableException e) {
            return null;
        }
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
                PageExtraction extraction = TextExtraction.extractWithOps(doc, pageIndex);
                List<TextRun> runs = extraction.runs();
                // Which edited lines have their original letters cut out of the file rather than covered.
                Set<Integer> cutOut = saved.runs().isEmpty() ? Set.of()
                        : TextRemoval.plan(doc, pageIndex, extraction, saved.runs().keySet()).runs();
                RunPainter.PageGlyphs pageGlyphs = RunPainter.PageGlyphs.of(runs);
                Map<COSDictionary, FontMatcher.Match> matchByFont = new IdentityHashMap<>();
                Map<COSDictionary, Boolean> missingByFont = new IdentityHashMap<>();
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
                            existing == null ? 0f : existing.dx(), existing == null ? 0f : existing.dy(),
                            pageGlyphs.charsOf(run),
                            run.sourceFont() != null && missingByFont.computeIfAbsent(
                                    run.sourceFont().getCOSObject(), k -> fontMatcher.isMissing(run.sourceFont())),
                            existing != null && cutOut.contains(i),
                            Double.isNaN(match.similarity) ? null : (float) match.similarity,
                            existing == null ? List.of() : existing.spans()));
                }
            }
            return new PageTextDto(crop.getWidth(), crop.getHeight(), crop.getLowerLeftX(),
                    crop.getLowerLeftY(), rotated, dtos, saved.added(), saved.imageBoxes());
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
        List<RunEdit.Span> spans = RunEdit.cleanSpans(item.spans(), text.length());
        if (spans.size() > MAX_SPANS) {
            throw new InvalidEditException("한 줄에는 서식을 " + MAX_SPANS + "군데까지 줄 수 있습니다.");
        }
        for (RunEdit.Span span : spans) {
            if (span.color() != null && !span.color().matches(COLOUR_PATTERN)) {
                throw new InvalidEditException("글자 색은 #rrggbb 형식이어야 합니다.");
            }
            if (span.size() != null && !(span.size() >= MIN_FONT_SIZE && span.size() <= MAX_FONT_SIZE)) {
                throw new InvalidEditException("글자 크기는 " + (int) MIN_FONT_SIZE + "~" + (int) MAX_FONT_SIZE + "pt 사이여야 합니다.");
            }
        }
        return new RunEdit(text, family, size, item.bold(), color, dx, dy, spans);
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
            AddedText.Cover cover = box.cover();
            if (cover != null && !(cover.color() != null && cover.color().matches(COLOUR_PATTERN)
                    && Math.abs(cover.x()) <= MAX_OFFSET && Math.abs(cover.y()) <= MAX_OFFSET
                    && cover.width() > 0 && cover.width() <= MAX_OFFSET
                    && cover.height() > 0 && cover.height() <= MAX_OFFSET)) {
                throw new InvalidEditException("고쳐 쓴 글자의 바탕 영역이 올바르지 않습니다.");
            }
            if (cover != null && cover.patch() != null) {
                checkPatch(cover.patch());
            }
            if (!(Math.abs(box.rotation()) <= 360)) {
                throw new InvalidEditException("텍스트 상자의 회전 각도가 올바르지 않습니다.");
            }
            String text = box.text() == null ? "" : box.text();
            if (text.length() > MAX_ADDED_LENGTH) {
                throw new InvalidEditException("텍스트 상자 하나에는 " + MAX_ADDED_LENGTH + "자까지 쓸 수 있습니다.");
            }
            List<RunEdit.Span> spans = RunEdit.cleanSpans(box.spans(), text.length());
            if (spans.size() > MAX_SPANS) {
                throw new InvalidEditException("한 상자에는 서식을 " + MAX_SPANS + "군데까지 줄 수 있습니다.");
            }
            for (RunEdit.Span span : spans) {
                if (span.color() != null && !span.color().matches(COLOUR_PATTERN)) {
                    throw new InvalidEditException("글자 색은 #rrggbb 형식이어야 합니다.");
                }
                if (span.size() != null && !(span.size() >= MIN_FONT_SIZE && span.size() <= MAX_FONT_SIZE)) {
                    throw new InvalidEditException("글자 크기는 " + (int) MIN_FONT_SIZE + "~" + (int) MAX_FONT_SIZE + "pt 사이여야 합니다.");
                }
            }
            if (!text.isBlank()) {
                fontMatcher.checkCovers(box.fontFamily(), box.bold(), text);
                // A part in the other weight needs that weight's font to have its letters too.
                for (RunEdit.Span span : spans) {
                    if (span.bold() != null && span.bold() != box.bold()) {
                        fontMatcher.checkCovers(box.fontFamily(), span.bold(), text.substring(span.start(), span.end()));
                    }
                }
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
        PageExtraction extraction = TextExtraction.extractWithOps(srcDoc, pageIndex);
        List<TextRun> allRuns = extraction.runs();
        RunPainter.PageGlyphs pageGlyphs = RunPainter.PageGlyphs.of(allRuns);
        PDRectangle crop = srcDoc.getPage(pageIndex).getCropBox();

        // Lines whose original letters can be cut out of the page are not covered at all.
        TextRemoval.Plan cuts = edits.runs().isEmpty() ? TextRemoval.Plan.NONE
                : TextRemoval.plan(srcDoc, pageIndex, extraction, edits.runs().keySet());
        if (!cuts.isEmpty()) {
            TextRemoval.apply(srcDoc.getPage(pageIndex), outDoc, outPage, cuts.ops());
        }
        boolean needsCover = edits.runs().keySet().stream()
                .anyMatch(i -> i >= 0 && i < allRuns.size() && !cuts.runs().contains(i));

        // The page as it looks before any edit, and the same page without its text: comparing the two
        // shows where the letters' ink is, and the second is what covers it - so the cover-up matches a
        // coloured table cell, and leaves the table rules next to the text alone.
        BufferedImage backdrop = needsCover ? new PDFRenderer(srcDoc).renderImage(pageIndex, BACKDROP_SCALE) : null;
        BufferedImage clean = backdrop == null ? null : textFree(srcPdf, pageIndex);
        try (PDPageContentStream cs = new PDPageContentStream(outDoc, outPage,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            for (Map.Entry<Integer, RunEdit> entry : edits.runs().entrySet()) {
                int runIndex = entry.getKey();
                if (runIndex < 0 || runIndex >= allRuns.size()) {
                    continue;
                }
                applyRunEdit(cs, outDoc, allRuns, allRuns.get(runIndex), entry.getValue(), pageGlyphs, backdrop,
                        clean, crop, fonts, fontScope, cuts.runs().contains(runIndex));
            }
            for (PageEdits.PlacedImage placed : edits.images()) {
                drawImage(cs, outDoc, placed, fonts);
            }
            for (AddedText box : edits.added()) {
                drawAdded(cs, outDoc, box, fonts);
            }
        }
    }

    private void drawImage(PDPageContentStream cs, PDDocument outDoc, PageEdits.PlacedImage placed, FontCache fonts)
            throws IOException {
        AddedImage box = placed.box();
        String key = box.imageId() + "|" + box.keyOutWhite();
        PDImageXObject image = fonts.images.get(key);
        if (image == null) {
            try {
                image = ImageDrawing.create(outDoc, placed.data(), box.keyOutWhite());
            } catch (IOException | RuntimeException unreadable) {
                log.warn("A picture on the page could not be drawn: {}", unreadable.toString());
                return;
            }
            fonts.images.put(key, image);
        }
        cs.drawImage(image, box.x(), box.y(), box.width(), box.height());
    }

    /** Checks the picture boxes submitted for a page and returns them, ready to store. */
    public List<AddedImage> checkImages(byte[] pdf, int pageIndex, List<AddedImage> boxes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            if (isRotated(doc.getPage(pageIndex))) {
                throw new PageNotEditableException("회전된 페이지에는 그림을 넣을 수 없습니다.");
            }
        }
        ImageDrawing.checkBoxes(boxes);
        return boxes;
    }

    private void applyRunEdit(PDPageContentStream cs, PDDocument outDoc, List<TextRun> allRuns, TextRun original,
            RunEdit edit, RunPainter.PageGlyphs pageGlyphs, BufferedImage backdrop, BufferedImage clean,
            PDRectangle crop, FontCache fonts, String fontScope, boolean cutOut) throws IOException {
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
        if (hasText && !edit.lineRestyled()) {
            layout = fonts.painter.layout(outDoc, original, text, pageGlyphs, fontScope,
                    edit.spans().isEmpty() ? List.of() : edit.segments()).orElse(null);
            if (layout != null) {
                boxWidth = Math.max(original.width(), layout.rightEdge() - original.x());
            }
        }

        FontMatcher.Styled styled = null;
        List<Piece> pieces = null;
        float fit = 1f;
        if (hasText && layout == null && !edit.spans().isEmpty()) {
            // Parts of the line have a look of their own: drawn piece by piece, each in its own weight and colour.
            pieces = piecesOf(outDoc, original, edit, text, size, colour, pageGlyphs, fonts, fontScope);
            fit = widthFit(pieces.get(0).styled().font(), original, edit, size);
            boxWidth = Math.max(original.width(), widthOf(pieces) * fit);
        } else if (hasText && layout == null) {
            styled = fontFor(outDoc, original, edit, text, size, pageGlyphs, fonts, fontScope);
            fit = widthFit(styled.font(), original, edit, size);
            Float measured = tryMeasureWidth(styled.font(), text, size);
            // The new text may render wider than the original run; a white-out only as wide
            // as the original would expose original text under the spilled tail.
            boxWidth = Math.max(original.width(),
                    (measured != null ? measured : text.length() * size * 0.6f) * fit);
        }

        // A moved line leaves its old place behind: only that place is covered, the line is
        // drawn again where it now is.
        float coverX = original.x() - 1;
        float coverWidth = (edit.moved() ? original.width() : boxWidth) + 2;
        if (cutOut) {
            // The original letters are no longer in the page: there is nothing to cover.
        } else if (clean != null) {
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
        } else if (pieces != null) {
            float cursor = original.x() + edit.dx();
            float baseline = original.y() + edit.dy();
            for (Piece piece : pieces) {
                drawLine(cs, piece.styled(), piece.text(), cursor, baseline, piece.size(), piece.colour(), fit);
                float width = widthOf(piece) * fit;
                if (piece.underline()) {
                    underline(cs, cursor, cursor + width, baseline, piece.size(), piece.colour());
                }
                cursor += width;
            }
        } else if (hasText) {
            drawLine(cs, styled, text, original.x() + edit.dx(), original.y() + edit.dy(), size, colour, fit);
        }
    }

    /** One stretch of an edited line with its own font (weight), colour, size and underline. */
    private record Piece(String text, FontMatcher.Styled styled, Color colour, float size, boolean underline) {
    }

    /** A line under {@code x0} to {@code x1}, below the baseline by the usual fraction of the size. */
    private static void underline(PDPageContentStream cs, float x0, float x1, float baseline, float size, Color colour)
            throws IOException {
        cs.saveGraphicsState();
        try {
            cs.setStrokingColor(colour);
            cs.setLineWidth(Math.max(0.3f, size * 0.05f));
            cs.moveTo(x0, baseline - size * 0.12f);
            cs.lineTo(x1, baseline - size * 0.12f);
            cs.stroke();
        } finally {
            cs.restoreGraphicsState();
        }
    }

    /** The line cut where its look changes, each stretch with the font and colour it is drawn in. */
    private List<Piece> piecesOf(PDDocument outDoc, TextRun original, RunEdit edit, String text, float size,
            Color lineColour, RunPainter.PageGlyphs pageGlyphs, FontCache fonts, String fontScope) {
        // Every piece is set in the same stand-in family, in the weight it asks for or else the line's.
        boolean lineBold = edit.bold() != null ? edit.bold() : (original.sourceFont() == null
                ? FontMatcher.guess(original.sourceFontName(), null)
                : fontMatcher.matchFor(original.sourceFont(), pageGlyphs)).bold;
        List<Piece> pieces = new ArrayList<>();
        for (RunEdit.Segment segment : edit.segments()) {
            String part = text.substring(segment.start(), segment.end());
            boolean bold = segment.bold() != null ? segment.bold() : lineBold;
            RunEdit forPiece = new RunEdit(part, edit.fontFamily(), edit.fontSize(), bold, edit.color(), 0f, 0f);
            Color colour = segment.color() != null ? RunEdit.colour(segment.color(), lineColour) : lineColour;
            float pieceSize = segment.size() != null ? segment.size() : size;
            pieces.add(new Piece(part,
                    fontFor(outDoc, original, forPiece, part, pieceSize, pageGlyphs, fonts, fontScope),
                    colour, pieceSize, Boolean.TRUE.equals(segment.underline())));
        }
        return pieces;
    }

    private static float widthOf(Piece piece) {
        Float measured = tryMeasureWidth(piece.styled().font(), piece.text(), piece.size());
        return measured != null ? measured : piece.text().length() * piece.size() * 0.6f;
    }

    private static float widthOf(List<Piece> pieces) {
        float total = 0f;
        for (Piece piece : pieces) {
            total += widthOf(piece);
        }
        return total;
    }

    /** Narrowest and widest a substitute font is squeezed or stretched to match the original's width. */
    private static final float MIN_FIT = 0.7f;
    private static final float MAX_FIT = 1.15f;

    /**
     * How much wider or narrower than natural a line set in a stand-in font is drawn so that it takes the
     * room the original text did. The original wording is measured in the stand-in font and compared with
     * the width it really had, so the same sentence comes out as wide as before whatever font stands in.
     * A font the user picked is drawn as it is, and so is a line whose original cannot be measured.
     */
    static float widthFit(PDFont font, TextRun original, RunEdit edit, float size) {
        if (edit.fontFamily() != null || original.width() < 1f || original.fontSize() <= 0f) {
            return 1f;
        }
        String reference = original.text().strip();
        Float natural = reference.isEmpty() ? null : tryMeasureWidth(font, reference, size);
        if (natural == null || natural < 1f) {
            return 1f;
        }
        float target = original.width() * (size / original.fontSize());
        float fit = target / natural;
        if (fit > 0.97f && fit < 1.03f) {
            return 1f; // already as wide as it was: leave the letters alone
        }
        return Math.max(MIN_FIT, Math.min(MAX_FIT, fit));
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

    /**
     * Paints over the scanned letters a box rewrites: with its patch (the paper's colour on the letters only, so
     * the table rules around them stay), or for a box without one with a plain fill in the paper's colour.
     */
    private static void drawCover(PDPageContentStream cs, PDDocument outDoc, AddedText.Cover cover) throws IOException {
        if (cover.patch() != null) {
            try {
                BufferedImage patch = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(cover.patch())));
                if (patch != null) {
                    cs.drawImage(LosslessFactory.createFromImage(outDoc, patch), cover.x(), cover.y(), cover.width(),
                            cover.height());
                    return;
                }
            } catch (IOException | IllegalArgumentException unreadable) {
                log.warn("The cover of a rewritten line could not be read, filling it instead: {}", unreadable.toString());
            }
        }
        cs.saveGraphicsState();
        cs.setNonStrokingColor(RunEdit.colour(cover.color(), Color.WHITE));
        cs.addRect(cover.x(), cover.y(), cover.width(), cover.height());
        cs.fill();
        cs.restoreGraphicsState();
    }

    /** A cover's patch must be a PNG of a sensible size (it is decoded for every preview and export). */
    private static void checkPatch(String patch) {
        if (patch.length() > MAX_PATCH_CHARS) {
            throw new InvalidEditException("고쳐 쓴 글자의 바탕 그림이 너무 큽니다.");
        }
        try {
            long pixels = ImageDrawing.headerPixels(Base64.getDecoder().decode(patch));
            if (pixels <= 0 || pixels > MAX_PATCH_PIXELS) {
                throw new InvalidEditException("고쳐 쓴 글자의 바탕 그림이 올바르지 않습니다.");
            }
        } catch (IOException | IllegalArgumentException unreadable) {
            throw new InvalidEditException("고쳐 쓴 글자의 바탕 그림이 올바르지 않습니다.");
        }
    }

    private void drawAdded(PDPageContentStream cs, PDDocument outDoc, AddedText box, FontCache fonts)
            throws IOException {
        if (box.cover() != null) {
            drawCover(cs, outDoc, box.cover());
        }
        if (box.text() == null || box.text().isBlank()) {
            return;
        }
        List<BoxLine> lines = layout(outDoc, box, fonts);
        boolean turned = box.rotation() != 0;
        if (turned) {
            cs.saveGraphicsState();
            cs.transform(turn(box));
        }
        for (BoxLine line : lines) {
            if (line.text().isBlank()) {
                continue;
            }
            float cursor = box.x();
            for (Piece piece : line.pieces()) {
                drawLine(cs, piece.styled(), piece.text(), cursor, line.baseline(), piece.size(), piece.colour());
                float width = widthOf(piece);
                if (piece.underline()) {
                    underline(cs, cursor, cursor + width, line.baseline(), piece.size(), piece.colour());
                }
                cursor += width;
            }
        }
        if (turned) {
            cs.restoreGraphicsState();
        }
    }

    /** A line of a text box: its text, its pieces in their looks, and its baseline (before the box is turned). */
    private record BoxLine(String text, List<Piece> pieces, float baseline) {
    }

    /** The lines of a text box, each cut where the look changes into pieces in the weight, colour and size asked for. */
    private List<BoxLine> layout(PDDocument outDoc, AddedText box, FontCache fonts) {
        Color boxColour = RunEdit.colour(box.color(), Color.BLACK);
        List<RunEdit.Segment> segments = new RunEdit(box.text(), null, null, null, null, 0f, 0f, box.spans()).segments();
        String[] lines = box.text().split("\\R", -1);
        List<BoxLine> out = new ArrayList<>(lines.length);
        float baseline = box.y();
        int lineStart = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineEnd = lineStart + line.length();
            float tallest = box.fontSize();
            List<Piece> pieces = new ArrayList<>();
            for (RunEdit.Segment segment : segments) {
                int from = Math.max(segment.start(), lineStart);
                int to = Math.min(segment.end(), lineEnd);
                if (to <= from) {
                    continue;
                }
                boolean bold = segment.bold() != null ? segment.bold() : box.bold();
                float size = segment.size() != null ? segment.size() : box.fontSize();
                String key = "family:" + (box.fontFamily() == null ? "" : box.fontFamily()) + "|" + bold;
                FontMatcher.Styled styled = fonts.styled.computeIfAbsent(key,
                        k -> fontMatcher.loadStyled(outDoc, box.fontFamily(), bold));
                Color colour = segment.color() != null ? RunEdit.colour(segment.color(), boxColour) : boxColour;
                pieces.add(new Piece(box.text().substring(from, to), styled, colour, size,
                        Boolean.TRUE.equals(segment.underline())));
                tallest = Math.max(tallest, size);
            }
            if (i > 0) {
                baseline -= tallest * LINE_SPACING;
            }
            out.add(new BoxLine(line, pieces, baseline));
            lineStart = lineEnd + lineBreakLength(box.text(), lineEnd);
        }
        return out;
    }

    /** The turn of a box about its first baseline's start, (x, y). */
    private static Matrix turn(AddedText box) {
        double a = Math.toRadians(box.rotation());
        float cos = (float) Math.cos(a);
        float sin = (float) Math.sin(a);
        return new Matrix(cos, sin, -sin, cos, box.x() - cos * box.x() + sin * box.y(),
                box.y() - sin * box.x() - cos * box.y());
    }

    /**
     * Where the letters of the text boxes are, so they can be looked through like the page's own text (for
     * personal information): a line per box, its lines joined by a space - or by nothing for a box holding a
     * letter a line, a label rewritten letter under letter. Measured in the fonts the boxes are drawn in.
     */
    public List<TextLines.Line> addedLines(List<AddedText> boxes) {
        List<TextLines.Line> out = new ArrayList<>();
        if (boxes.stream().allMatch(b -> b.text() == null || b.text().isBlank())) {
            return out;
        }
        try (PDDocument scratch = new PDDocument()) {
            FontCache fonts = newFontCache();
            for (AddedText box : boxes) {
                if (box.text() == null || box.text().isBlank()) {
                    continue;
                }
                List<BoxLine> lines;
                try {
                    lines = layout(scratch, box, fonts);
                } catch (RuntimeException noFont) {
                    continue; // a box whose font is gone cannot be drawn either
                }
                boolean letterALine = lines.size() > 1
                        && lines.stream().allMatch(l -> l.text().codePointCount(0, l.text().length()) <= 1);
                StringBuilder text = new StringBuilder();
                List<TextLines.Area> areas = new ArrayList<>();
                for (int i = 0; i < lines.size(); i++) {
                    if (i > 0 && !letterALine) {
                        text.append(' ');
                        areas.add(null);
                    }
                    float cursor = box.x();
                    for (Piece piece : lines.get(i).pieces()) {
                        for (int c = 0; c < piece.text().length(); c++) {
                            String letter = piece.text().substring(c, c + 1);
                            Float measured = tryMeasureWidth(piece.styled().font(), letter, piece.size());
                            float width = measured != null ? measured : piece.size() * 0.6f;
                            areas.add(turned(box, new TextLines.Area(cursor, lines.get(i).baseline() - 0.25f * piece.size(),
                                    width, 1.1f * piece.size())));
                            text.append(piece.text().charAt(c));
                            cursor += width;
                        }
                    }
                }
                out.add(TextLines.Line.of(text.toString(), areas));
            }
        } catch (IOException e) {
            log.warn("The text boxes could not be measured: {}", e.toString());
        }
        return out;
    }

    /** The area as it lies on the page once the box is turned: the rectangle around the turned one. */
    private static TextLines.Area turned(AddedText box, TextLines.Area area) {
        if (box.rotation() == 0) {
            return area;
        }
        Matrix m = turn(box);
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (float[] corner : new float[][] {{area.x(), area.y()}, {area.x() + area.width(), area.y()},
                {area.x(), area.y() + area.height()}, {area.x() + area.width(), area.y() + area.height()}}) {
            java.awt.geom.Point2D.Float p = m.transformPoint(corner[0], corner[1]);
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
        }
        return new TextLines.Area(minX, minY, maxX - minX, maxY - minY);
    }

    /** How many characters the line break at {@code at} takes: 2 for \r\n, 1 for any other, 0 at the end of the text. */
    private static int lineBreakLength(String text, int at) {
        if (at >= text.length()) {
            return 0;
        }
        return text.charAt(at) == '\r' && at + 1 < text.length() && text.charAt(at + 1) == '\n' ? 2 : 1;
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
        drawLine(cs, styled, text, x, y, fontSize, colour, 1f);
    }

    /** {@code hScale}: 1 is the font's natural width, 0.9 draws the letters 10% narrower. */
    private static void drawLine(PDPageContentStream cs, FontMatcher.Styled styled, String text, float x, float y,
            float fontSize, Color colour, float hScale) throws IOException {
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
                if (hScale != 1f) {
                    cs.setHorizontalScaling(hScale * 100f);
                }
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
