package com.pdfedit.dto;

import java.util.List;

import com.pdfedit.compare.DocumentComparer.Change;

/**
 * Two documents compared: their pages (size and origin, to place the changes on a picture of a page) and
 * the text removed from the first and added in the second.
 */
public record CompareResult(String a, String b, String fileNameA, String fileNameB, List<PageSize> pagesA,
                            List<PageSize> pagesB, List<Change> removed, List<Change> added, int removedWords,
                            int addedWords) {

    /** A page's crop box: its size and where its lower left corner is, in points. */
    public record PageSize(float width, float height, float originX, float originY) {
    }
}
