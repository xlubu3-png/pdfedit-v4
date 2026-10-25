package com.pdfedit.dto;

/** One piece of personal information found, and the area (PDF user space, points) that would redact it. */
public record PersonalInfoMatch(String documentId, int pageIndex, String kind, String label, String text,
                                float x, float y, float width, float height) {
}
