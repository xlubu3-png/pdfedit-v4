package com.pdfedit.dto;

/**
 * {@code unlocked}: the upload was encrypted and is kept, and exported, without its password.
 * {@code permissions}: what the encrypted original allowed, null when it was not encrypted; sent back with an
 * export that is to be locked again ({@link ExportRequest#permissions()}).
 */
public record UploadResponse(String documentId, String fileName, int pageCount, boolean unlocked,
                             Integer permissions) {

    public UploadResponse(String documentId, String fileName, int pageCount) {
        this(documentId, fileName, pageCount, false, null);
    }
}
