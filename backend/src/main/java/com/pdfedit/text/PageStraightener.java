package com.pdfedit.text;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;

/**
 * Sets a scanned page straight: a sheet fed a little askew (0.25 - 5 degrees) is turned back, as {@link Deskew}
 * measures it. The page's drawing is turned, not its picture redrawn: the scan keeps its quality and size, and the
 * corners turned in from outside the page are paper (nothing is drawn there). Only a page that is a picture is
 * turned - a page with real text was made straight.
 */
public final class PageStraightener {

    /** The page is looked at this sharp to measure it (Deskew works on about 1000 pixels anyway). */
    private static final float DPI = 100;
    /** A page with more letters of real text than this is not a scan. */
    private static final int MAX_TEXT = 20;

    private PageStraightener() {
    }

    /**
     * Degrees to turn the page clockwise (as it is seen) to set it straight; 0 when it is straight enough, its slant
     * cannot be told, or it is not a scan.
     */
    public static double angle(PDDocument document, int pageIndex) throws IOException {
        if (hasText(document, pageIndex)) {
            return 0;
        }
        BufferedImage seen = new PDFRenderer(document).renderImageWithDPI(pageIndex, DPI, ImageType.GRAY);
        return Deskew.angle(seen);
    }

    /** The page as a document of its own, turned clockwise by {@code degrees} about its middle. */
    public static byte[] straightened(byte[] pdf, int pageIndex, double degrees) throws IOException {
        try (PDDocument source = Loader.loadPDF(pdf);
                PDDocument copy = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = copy.importPage(source.getPage(pageIndex));
            turn(copy, page, degrees);
            copy.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Turns what the page draws by {@code degrees} clockwise, as it is seen, about the middle of its visible area.
     * A page shown turned (/Rotate) is seen turned, so the drawing is turned the same way in its own space.
     */
    static void turn(PDDocument document, PDPage page, double degrees) throws IOException {
        PDRectangle box = page.getCropBox();
        double cx = box.getLowerLeftX() + box.getWidth() / 2;
        double cy = box.getLowerLeftY() + box.getHeight() / 2;
        // PDF space has y up: clockwise as seen is a negative angle there. /Rotate turns the whole page clockwise,
        // which does not change the sense of a turn within it.
        double radians = -Math.toRadians(degrees);
        Matrix turn = Matrix.getTranslateInstance((float) cx, (float) cy);
        turn.concatenate(Matrix.getRotateInstance(radians, 0, 0));
        turn.concatenate(Matrix.getTranslateInstance((float) -cx, (float) -cy));
        try (PDPageContentStream before = new PDPageContentStream(document, page,
                PDPageContentStream.AppendMode.PREPEND, true)) {
            before.saveGraphicsState();
            before.transform(turn);
        }
        try (PDPageContentStream after = new PDPageContentStream(document, page,
                PDPageContentStream.AppendMode.APPEND, true)) {
            after.restoreGraphicsState();
        }
    }

    private static boolean hasText(PDDocument document, int pageIndex) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        return stripper.getText(document).replaceAll("\\s", "").length() > MAX_TEXT;
    }
}
