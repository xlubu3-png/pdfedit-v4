package com.pdfedit.dto;

import java.util.List;

/**
 * @param flatten     true to turn every page into a picture of itself: nothing of the original text,
 *                    edited or not, stays in the file (and nothing can be searched or copied in it)
 * @param password    when not blank, the exported file is locked with it (AES-256)
 * @param permissions what the locked file allows, as {@link UploadResponse#permissions()}; null allows all
 */
public record ExportRequest(List<PageSpec> pages, String fileName, Boolean flatten, String password,
                            Integer permissions) {

    public ExportRequest(List<PageSpec> pages, String fileName) {
        this(pages, fileName, null, null, null);
    }
}
