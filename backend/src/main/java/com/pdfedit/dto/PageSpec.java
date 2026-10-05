package com.pdfedit.dto;

/**
 * One page in the exported output: {@code pageIndex} is 0-based into the
 * source document identified by {@code documentId}; {@code rotation} is a
 * clockwise degree delta (0/90/180/270) applied on top of the page's
 * existing rotation.
 */
public record PageSpec(String documentId, int pageIndex, int rotation) {
}
