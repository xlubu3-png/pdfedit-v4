package com.pdfedit.text;

/**
 * One text-showing operator (Tj, TJ, ' or ") of a page: which content stream it is in and how many
 * text-showing operators of that stream came before it. The page's own stream is {@code "page"}; a form
 * XObject is the path of the names it was drawn under, e.g. {@code "page/Fm1/Fm3"}.
 */
public record ShowOpRef(String stream, int ordinal) {

    /** Stands for a character that no operator of a content stream could be found for (a Type 3 glyph's own text). */
    public static final ShowOpRef UNKNOWN = new ShowOpRef("?", -1);
}
