package com.pdfedit.dto;

import java.util.List;

/**
 * Search the text lines of these pages (in the order the user has them) for {@code query}. {@code
 * rotation} of each {@link PageSpec} is ignored.
 */
public record FindRequest(List<PageSpec> pages, String query, Boolean matchCase) {
}
