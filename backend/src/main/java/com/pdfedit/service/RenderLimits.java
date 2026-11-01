package com.pdfedit.service;

import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * Keeps the pictures drawn for the screen (previews, thumbnails) to a size the memory can take. A large-format
 * page - an A0 drawing, a long banner - at the asked resolution would need hundreds of megabytes for a single
 * picture; the screen scales the picture to the page's width anyway, so fewer pixels only make it less sharp.
 */
public final class RenderLimits {

    /** About 100 MB as ARGB: an A4 page at 300 DPI (8.7 million pixels) stays well under it. */
    public static final long MAX_PIXELS = 25_000_000L;

    private RenderLimits() {
    }

    /** {@code scale} (pixels per PDF unit), lowered just enough that the page fits {@link #MAX_PIXELS}. */
    public static float cappedScale(PDRectangle box, float scale) {
        double width = Math.max(1, box.getWidth());
        double height = Math.max(1, box.getHeight());
        double pixels = width * scale * height * scale;
        if (!(pixels > MAX_PIXELS)) {
            return scale;
        }
        return (float) Math.sqrt(MAX_PIXELS / (width * height));
    }

    /** {@code dpi} lowered the same way. */
    public static float cappedDpi(PDRectangle box, float dpi) {
        return cappedScale(box, dpi / 72f) * 72f;
    }
}
