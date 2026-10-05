package com.pdfedit.dto;

import java.util.List;

import com.pdfedit.text.AddedText;

/**
 * Text runs of one page plus the geometry needed to map them onto the rendered preview image.
 *
 * @param pageWidth  width in points of the area the preview image shows (the crop box)
 * @param pageHeight height in points of that area
 * @param originX    user-space x of that area's lower-left corner (subtract from run x)
 * @param originY    user-space y of that area's lower-left corner
 * @param rotated    true when the page carries a /Rotate entry; run coordinates are not mapped back
 *                   to the unrotated space for such pages, so the UI must not offer editing
 * @param added      the text boxes the user has put on this page
 */
public record PageTextDto(float pageWidth, float pageHeight, float originX, float originY,
                          boolean rotated, List<TextRunDto> runs, List<AddedText> added) {
}
