package com.pdfedit.dto;

/** One text line that contains the searched text; {@code text} is the line as it reads now (edits included). */
public record FindMatch(String documentId, int pageIndex, int runIndex, String text) {
}
