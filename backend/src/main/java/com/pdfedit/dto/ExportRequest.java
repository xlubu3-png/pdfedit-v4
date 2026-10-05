package com.pdfedit.dto;

import java.util.List;

/**
 * @param flatten true to turn every page into a picture of itself: nothing of the original text,
 *                edited or not, stays in the file (and nothing can be searched or copied in it)
 */
public record ExportRequest(List<PageSpec> pages, String fileName, Boolean flatten) {

    public ExportRequest(List<PageSpec> pages, String fileName) {
        this(pages, fileName, null);
    }
}
