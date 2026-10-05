package com.pdfedit.dto;

import java.util.List;

/**
 * What a replace did.
 *
 * @param replacements how many occurrences were replaced
 * @param runsChanged  how many text lines changed
 * @param pages        the pages that changed (their previews and thumbnails are out of date)
 * @param skipped      why some pages were left alone (e.g. the font lacks a character), or null
 */
public record ReplaceResult(int replacements, int runsChanged, List<PageSpec> pages, String skipped) {
}
