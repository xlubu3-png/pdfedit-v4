package com.pdfedit.redact;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Takes what lies under the redaction areas out of a page for good, paints the areas black and then checks
 * that it worked.
 *
 * <p>Letters under an area are cut out of the page's content glyph by glyph ({@link GlyphCutter}); the
 * letters next to them stay real, searchable text. Afterwards the page is read again, in two independent
 * ways (the glyphs as drawn and the text as a text extractor sees it): if anything is still found inside an
 * area, or something under an area cannot be cut out (a picture, vertical writing, a form drawn twice), the
 * page is replaced by a picture of itself with the areas painted into the pixels. Either way nothing that was
 * under an area is left in the file.
 */
public final class Redactor {

    static final float RASTER_DPI = 200f;

    private Redactor() {
    }

    /**
     * What happened to a page.
     *
     * @param glyphsRemoved the letters cut out
     * @param rasterized    whether the page had to be made a picture
     * @param reason        why it had to, or null
     */
    public record Result(int areas, int glyphsRemoved, boolean rasterized, String reason) {
    }

    /** Redacts the page, which must belong to {@code doc}; the page is changed in place. */
    public static Result apply(PDDocument doc, PDPage page, List<Redaction> areas) throws IOException {
        if (areas.isEmpty()) {
            return new Result(0, 0, false, null);
        }
        PageScan scan = PageScan.of(page);
        List<PageScan.GlyphAt> hits = scan.glyphs.stream().filter(g -> inside(areas, g.centerX(), g.centerY())).toList();
        String reason = null;
        for (PageScan.ImageAt image : scan.images) {
            if (overlapsAny(areas, image.minX(), image.minY(), image.maxX(), image.maxY())) {
                reason = "가림 영역에 그림이 있음";
                break;
            }
        }
        if (reason == null) {
            for (PageScan.GlyphAt glyph : hits) {
                if (!glyph.rewritable() || scan.repeated(glyph.stream())
                        || !scan.fonts.containsKey(PageScan.opKey(glyph.stream(), glyph.op()))) {
                    reason = "따로 지울 수 없는 글자가 있음";
                    break;
                }
            }
        }
        if (reason == null) {
            try {
                if (!hits.isEmpty()) {
                    GlyphCutter.cut(doc, page, hits, scan.fonts);
                }
                paint(doc, page, areas);
                removeAnnotations(page, areas, false);
                if (leftovers(doc, page, areas) > 0) {
                    reason = "확인 결과 글자가 남아 있음";
                }
            } catch (IOException | RuntimeException e) {
                reason = "글자를 지우지 못함";
            }
        }
        if (reason != null) {
            rasterize(doc, page, areas);
        }
        return new Result(areas.size(), hits.size(), reason != null, reason);
    }

    /** Paints the areas black on top of the page: what a preview shows before the real redaction on export. */
    public static void paint(PDDocument doc, PDPage page, List<Redaction> areas) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND,
                true, true)) {
            cs.setNonStrokingColor(0f);
            for (Redaction area : areas) {
                cs.addRect(area.x(), area.y(), area.width(), area.height());
            }
            cs.fill();
        }
    }

    /**
     * How many letters are still found inside the areas: once as the content draws its glyphs, once as a
     * text extractor reads the page. Zero after a successful redaction.
     */
    static int leftovers(PDDocument doc, PDPage page, List<Redaction> areas) throws IOException {
        int found = 0;
        for (PageScan.GlyphAt glyph : PageScan.of(page).glyphs) {
            if (inside(areas, glyph.centerX(), glyph.centerY())) {
                found++;
            }
        }
        return found + extractedInside(doc, page, areas);
    }

    private static int extractedInside(PDDocument doc, PDPage page, List<Redaction> areas) throws IOException {
        int pageIndex = doc.getPages().indexOf(page);
        if (pageIndex < 0) {
            return 0;
        }
        PDRectangle crop = page.getCropBox();
        float originX = crop.getLowerLeftX();
        float originY = crop.getLowerLeftY();
        float pageHeight = crop.getHeight();
        int[] count = {0};
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition text) {
                String unicode = text.getUnicode();
                if (unicode == null || unicode.isBlank()) {
                    return;
                }
                float cx = text.getXDirAdj() + text.getWidthDirAdj() / 2 + originX;
                float baseline = pageHeight - text.getYDirAdj() + originY;
                float cy = baseline + Math.max(text.getHeightDir(), 0.1f) / 2;
                if (inside(areas, cx, cy)) {
                    count[0]++;
                }
            }
        };
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.getText(doc);
        return count[0];
    }

    /**
     * Replaces the page by a picture of itself with the areas painted black into the pixels. Links outside the
     * areas are kept; every other annotation is in the picture already.
     */
    static void rasterize(PDDocument doc, PDPage page, List<Redaction> areas) throws IOException {
        int pageIndex = doc.getPages().indexOf(page);
        PDRectangle crop = page.getCropBox();
        int rotation = page.getRotation();
        page.setRotation(0);
        BufferedImage image;
        try {
            image = new PDFRenderer(doc).renderImageWithDPI(pageIndex, RASTER_DPI, ImageType.RGB);
        } finally {
            page.setRotation(rotation);
        }
        float scale = RASTER_DPI / 72f;
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.BLACK);
            for (Redaction area : areas) {
                double left = Math.floor((area.x() - crop.getLowerLeftX()) * scale) - 1;
                double top = Math.floor((crop.getUpperRightY() - (area.y() + area.height())) * scale) - 1;
                g.fill(new Rectangle2D.Double(left, top, Math.ceil(area.width() * scale) + 2,
                        Math.ceil(area.height() * scale) + 2));
            }
        } finally {
            g.dispose();
        }
        var picture = JPEGFactory.createFromImage(doc, image, 0.9f);
        page.setResources(new PDResources());
        try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE,
                true)) {
            cs.drawImage(picture, crop.getLowerLeftX(), crop.getLowerLeftY(), crop.getWidth(), crop.getHeight());
        }
        removeAnnotations(page, areas, true);
    }

    /** Drops the annotations that touch an area; with {@code allButLinks}, every annotation but a link elsewhere. */
    private static void removeAnnotations(PDPage page, List<Redaction> areas, boolean allButLinks) throws IOException {
        List<PDAnnotation> kept = new ArrayList<>();
        boolean changed = false;
        for (PDAnnotation annotation : page.getAnnotations()) {
            PDRectangle rect = annotation.getRectangle();
            boolean touches = rect == null || overlapsAny(areas, rect.getLowerLeftX(), rect.getLowerLeftY(),
                    rect.getUpperRightX(), rect.getUpperRightY());
            boolean keep = !touches && (!allButLinks || annotation instanceof PDAnnotationLink);
            if (keep) {
                kept.add(annotation);
            } else {
                changed = true;
            }
        }
        if (changed) {
            page.setAnnotations(kept);
        }
    }

    private static boolean inside(List<Redaction> areas, float x, float y) {
        for (Redaction area : areas) {
            if (area.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlapsAny(List<Redaction> areas, float minX, float minY, float maxX, float maxY) {
        for (Redaction area : areas) {
            if (area.overlaps(minX, minY, maxX, maxY)) {
                return true;
            }
        }
        return false;
    }
}
