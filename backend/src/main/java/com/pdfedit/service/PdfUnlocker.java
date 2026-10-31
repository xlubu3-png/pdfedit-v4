package com.pdfedit.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * An encrypted PDF is opened once with its password and kept as a plain copy, so every later step
 * (rendering, text extraction, editing, export) reads it like any other document. The exported file is
 * therefore no longer protected, unless it is locked again on export ({@link PdfProtector}).
 */
public final class PdfUnlocker {

    private PdfUnlocker() {
    }

    /**
     * The result of {@link #unlock}: the plain document and whether it had been encrypted.
     *
     * @param permissions what the encrypted original allowed (its /P bits), null when it was not encrypted;
     *                    kept so an export can be locked again the same way, unlike the password
     */
    public record Unlocked(byte[] content, boolean wasEncrypted, Integer permissions) {
    }

    /**
     * The document without its encryption. A document that needs a password it was not given, or a wrong
     * one, throws {@link org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException}.
     */
    public static Unlocked unlock(byte[] pdf, String password) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf, password == null ? "" : password)) {
            if (!doc.isEncrypted()) {
                return new Unlocked(pdf, false, null);
            }
            int permissions = doc.getEncryption().getPermissions();
            doc.setAllSecurityToBeRemoved(true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return new Unlocked(out.toByteArray(), true, permissions);
        }
    }
}
