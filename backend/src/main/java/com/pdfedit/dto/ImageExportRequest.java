package com.pdfedit.dto;

import java.util.List;

/**
 * 사진으로 내보내기: the pages, as they would be exported, drawn as pictures.
 *
 * @param fileName the name without an extension ("계약서_수정본"); the pictures are named after it
 * @param format   "jpg" (the default) or "png"
 * @param dpi      how sharp: 72 to 600, 200 when not given
 */
public record ImageExportRequest(List<PageSpec> pages, String fileName, String format, Float dpi) {
}
