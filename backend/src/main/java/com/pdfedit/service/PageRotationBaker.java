package com.pdfedit.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.util.Matrix;

/**
 * Pages that carry a {@code /Rotate} entry (scans and phone PDFs often do) are drawn in a rotated
 * coordinate system that the text editor does not map back. Instead of teaching every edit about
 * rotation, an uploaded document is rewritten once: each rotated page keeps looking exactly as it was
 * displayed, but its content is turned into an unrotated page of the displayed size.
 *
 * <p>Annotations (links, notes) on rotated pages are dropped, since their positions would no longer
 * match.
 */
public final class PageRotationBaker {

    private PageRotationBaker() {
    }

    /** The document with every rotated page baked into an unrotated one; the same bytes if none is rotated. */
    public static byte[] bake(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            boolean any = false;
            for (PDPage page : doc.getPages()) {
                any |= normalized(page.getRotation()) != 0;
            }
            if (!any) {
                return pdf;
            }
            for (PDPage page : doc.getPages()) {
                int rotation = normalized(page.getRotation());
                if (rotation != 0) {
                    bakePage(doc, page, rotation);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static int normalized(int rotation) {
        return ((rotation % 360) + 360) % 360;
    }

    private static void bakePage(PDDocument doc, PDPage page, int rotation) throws IOException {
        PDRectangle crop = page.getCropBox();
        float w = crop.getWidth();
        float h = crop.getHeight();
        float llx = crop.getLowerLeftX();
        float lly = crop.getLowerLeftY();

        // Where a point of the unrotated page lands once the page is turned clockwise, with the
        // crop box's corner moved to the origin: x' = a x + c y + e, y' = b x + d y + f.
        Matrix turn = switch (rotation) {
            case 90 -> new Matrix(0, -1, 1, 0, -lly, w + llx);
            case 180 -> new Matrix(-1, 0, 0, -1, w + llx, h + lly);
            default -> new Matrix(0, 1, -1, 0, h + lly, -llx); // 270
        };
        boolean swap = rotation != 180;
        PDRectangle shown = new PDRectangle(swap ? h : w, swap ? w : h);

        // "q <turn> cm" before the page's own content and "Q" after it: the content is turned, and
        // nothing that is drawn later (edits) inherits the turn.
        try (PDPageContentStream before = new PDPageContentStream(doc, page,
                PDPageContentStream.AppendMode.PREPEND, true, false)) {
            before.saveGraphicsState();
            before.transform(turn);
        }
        try (PDPageContentStream after = new PDPageContentStream(doc, page,
                PDPageContentStream.AppendMode.APPEND, true, false)) {
            after.restoreGraphicsState();
        }
        page.setMediaBox(shown);
        page.setCropBox(shown);
        page.setRotation(0);
        page.getCOSObject().removeItem(COSName.TRIM_BOX);
        page.getCOSObject().removeItem(COSName.BLEED_BOX);
        page.getCOSObject().removeItem(COSName.ART_BOX);
        page.setAnnotations(java.util.List.of());
    }
}
