package com.pdfedit.dto;

/**
 * The complete state of the run at {@code index} on a page (as numbered by {@code TextExtraction}):
 * its text, and what the user changed about how it looks and where it sits. Nulls (and zeros for
 * {@code dx}/{@code dy}) mean "as the original", so a reverted run is sent as plain original text.
 */
public record TextEditItem(int index, String text, String fontFamily, Float fontSize, Boolean bold, String color,
                           Float dx, Float dy) {

    public TextEditItem(int index, String text) {
        this(index, text, null, null, null, null, null, null);
    }
}
