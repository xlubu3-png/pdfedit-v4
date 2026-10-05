package com.pdfedit.dto;

/**
 * A font family the user can pick. {@code name} is what is sent back to select it; {@code label} is
 * what the list shows (the Korean name first, when the font has one); {@code korean} marks fonts
 * made for Hangul, which the list puts first.
 */
public record FontChoice(String name, String label, boolean korean) {
}
