package com.pdfedit.text;

/**
 * A picture (a stamp, a signature, a logo) the user put on a page. Like an {@link AddedText} it replaces
 * nothing of the original page: it is simply drawn on top.
 *
 * @param id          chosen by the browser, only to tell boxes apart
 * @param imageId     the uploaded picture this box shows; the same picture can sit in several boxes
 * @param x           left edge, PDF user space (origin bottom-left, y-up), in points
 * @param y           bottom edge, in points
 * @param width       in points
 * @param height      in points
 * @param keyOutWhite makes the white of the picture see-through (a photographed signature on paper)
 */
public record AddedImage(String id, String imageId, float x, float y, float width, float height,
                         boolean keyOutWhite) {
}
