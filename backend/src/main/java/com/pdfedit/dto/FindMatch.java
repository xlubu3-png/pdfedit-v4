package com.pdfedit.dto;

/**
 * One text line that contains the searched text; {@code text} is the line as it reads now (edits included).
 * {@code scanned}: the line was read off a scanned page by text recognition; it can be found, not replaced, and
 * its {@code runIndex} is negative (-1 for the page's first recognized line, -2 for the second, ...).
 */
public record FindMatch(String documentId, int pageIndex, int runIndex, String text, boolean scanned) {

    public FindMatch(String documentId, int pageIndex, int runIndex, String text) {
        this(documentId, pageIndex, runIndex, text, false);
    }
}
