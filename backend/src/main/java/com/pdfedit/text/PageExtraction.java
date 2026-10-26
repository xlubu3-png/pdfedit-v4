package com.pdfedit.text;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The text runs of a page together with where in the page's content they were drawn, which is what it
 * takes to cut a run out of the page instead of painting over it.
 *
 * @param runs     the runs, as {@link TextExtraction#extractRuns} returns them
 * @param runOps   for each run (same index), the operators that drew its characters
 * @param ops      every text-showing operator that drew anything, including ones whose characters are in
 *                 no run (duplicates drawn on top of each other for a faux bold, blanks)
 * @param repeated content streams (form XObjects) that the page draws more than once: an operator in one
 *                 of them cannot be cut out for a single place
 */
public record PageExtraction(List<TextRun> runs, List<Set<ShowOpRef>> runOps, Map<ShowOpRef, ShowOp> ops,
                             Set<String> repeated) {

    /**
     * What is known about one operator.
     *
     * @param operator   "Tj", "TJ", "'" or "\""
     * @param adjust     the TJ number that moves the text position on by as much as this operator's text did
     *                   (for ' and ", after their move to the next line), so that cutting the operator out
     *                   leaves the text after it where it was; NaN when the advance is not a plain horizontal
     *                   one (vertical writing)
     * @param minX       left edge of its characters in user space
     * @param maxX       right edge
     * @param baseline   baseline of its first character in user space
     * @param size       largest effective font size among its characters
     */
    public record ShowOp(ShowOpRef ref, String operator, double adjust, float minX, float maxX, float baseline,
                         float size) {

        /**
         * Whether it can be replaced by a plain advance: any text operator with a plain horizontal advance (a '
         * or " keeps its move to the next line, and " its spacing, in front of the advance).
         */
        public boolean cuttable() {
            return ("Tj".equals(operator) || "TJ".equals(operator) || "'".equals(operator) || "\"".equals(operator))
                    && !Double.isNaN(adjust);
        }
    }
}
