package com.pdfedit.redact;

/**
 * An area of a page whose content is taken out of the exported PDF and painted black.
 *
 * @param id     chosen by the browser, only to tell areas apart
 * @param x      left edge, PDF user space (origin bottom-left, y-up), in points
 * @param y      bottom edge, in points
 * @param width  in points
 * @param height in points
 */
public record Redaction(String id, float x, float y, float width, float height) {

    /** Whether the point is inside the area. */
    public boolean contains(float px, float py) {
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }

    /** Whether the rectangle shares any area with this one. */
    public boolean overlaps(float minX, float minY, float maxX, float maxY) {
        return minX < x + width && maxX > x && minY < y + height && maxY > y;
    }
}
