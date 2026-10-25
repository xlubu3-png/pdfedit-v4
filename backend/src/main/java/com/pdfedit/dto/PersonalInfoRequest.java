package com.pdfedit.dto;

import java.util.List;

/**
 * What to look for and where: the kinds are {@link com.pdfedit.redact.PersonalInfoFinder.Kind} names, and
 * {@code term} is plain text looked for as well (optional).
 */
public record PersonalInfoRequest(List<PageSpec> pages, List<String> kinds, String term) {
}
