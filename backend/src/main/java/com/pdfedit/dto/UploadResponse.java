package com.pdfedit.dto;

/** {@code unlocked}: the upload was encrypted and is kept, and exported, without its password. */
public record UploadResponse(String documentId, String fileName, int pageCount, boolean unlocked) {

    public UploadResponse(String documentId, String fileName, int pageCount) {
        this(documentId, fileName, pageCount, false);
    }
}
