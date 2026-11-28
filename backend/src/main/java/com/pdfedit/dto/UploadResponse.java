package com.pdfedit.dto;

/**
 * {@code unlocked}: the upload was encrypted and is kept, and exported, without its password.
 * {@code permissions}: what the encrypted original allowed, null when it was not encrypted; sent back with an
 * export that is to be locked again ({@link ExportRequest#permissions()}).
 * {@code scanned}: no page has any text, so it is probably a scan whose words only text recognition can read.
 * {@code convertedFrom}: the Word, Excel or PowerPoint file (or picture) it was made from; null for a PDF.
 * {@code signed}: the PDF carries a digital signature, which any change to it breaks (the screen says so).
 */
public record UploadResponse(String documentId, String fileName, int pageCount, boolean unlocked,
                             Integer permissions, boolean scanned, String convertedFrom, boolean signed) {

    public UploadResponse(String documentId, String fileName, int pageCount, boolean unlocked, Integer permissions,
            boolean scanned, String convertedFrom) {
        this(documentId, fileName, pageCount, unlocked, permissions, scanned, convertedFrom, false);
    }

    public UploadResponse(String documentId, String fileName, int pageCount, boolean unlocked, Integer permissions,
            boolean scanned) {
        this(documentId, fileName, pageCount, unlocked, permissions, scanned, null, false);
    }

    public UploadResponse(String documentId, String fileName, int pageCount) {
        this(documentId, fileName, pageCount, false, null, false, null, false);
    }
}
