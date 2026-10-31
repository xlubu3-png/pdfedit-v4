package com.pdfedit.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

/** Locks an exported PDF with a password again, for documents that came in protected. */
public final class PdfProtector {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PdfProtector() {
    }

    /**
     * The PDF encrypted with AES-256, opened by {@code password}.
     *
     * <p>The owner password is random and forgotten: opening the file with {@code password} keeps the
     * restrictions in force instead of lifting them, as with the original's own password. The editable copy
     * stays in this app.
     *
     * @param permissions what the original allowed ({@link PdfUnlocker.Unlocked#permissions()}); null allows all
     */
    public static byte[] lock(byte[] pdf, String password, Integer permissions) throws IOException {
        byte[] owner = new byte[24];
        RANDOM.nextBytes(owner);
        AccessPermission allowed = permissions == null ? new AccessPermission() : new AccessPermission(permissions);
        StandardProtectionPolicy policy = new StandardProtectionPolicy(
                Base64.getEncoder().encodeToString(owner), password, allowed);
        policy.setEncryptionKeyLength(256);
        try (PDDocument doc = Loader.loadPDF(pdf); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.protect(policy);
            doc.save(out);
            return out.toByteArray();
        }
    }
}
