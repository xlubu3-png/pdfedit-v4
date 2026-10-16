package com.pdfedit.dto;

import java.util.List;

import com.pdfedit.text.RunEdit;

/**
 * The complete state of the run at {@code index} on a page (as numbered by {@code TextExtraction}):
 * its text, and what the user changed about how it looks and where it sits. Nulls (and zeros for
 * {@code dx}/{@code dy}) mean "as the original", so a reverted run is sent as plain original text.
 */
public record TextEditItem(int index, String text, String fontFamily, Float fontSize, Boolean bold, String color,
                           Float dx, Float dy, List<RunEdit.Span> spans) {

    public TextEditItem(int index, String text, String fontFamily, Float fontSize, Boolean bold, String color,
                        Float dx, Float dy) {
        this(index, text, fontFamily, fontSize, bold, color, dx, dy, null);
    }

    public TextEditItem(int index, String text) {
        this(index, text, null, null, null, null, null, null, null);
    }
}
