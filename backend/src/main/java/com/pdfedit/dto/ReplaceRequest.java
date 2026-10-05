package com.pdfedit.dto;

import java.util.List;

/** Replace {@code find} by {@code replace} in the text lines of these pages ({@code rotation} is ignored). */
public record ReplaceRequest(List<PageSpec> pages, String find, String replace, Boolean matchCase) {
}
