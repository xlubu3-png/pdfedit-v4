package com.pdfedit.service;

import java.util.List;
import java.util.Map;

import com.pdfedit.text.AddedText;
import com.pdfedit.text.RunEdit;

/** Everything the user changed on one page: edits of existing text runs, and boxes added to it. */
public record PageEdits(Map<Integer, RunEdit> runs, List<AddedText> added) {

    public static final PageEdits NONE = new PageEdits(Map.of(), List.of());

    public static PageEdits ofRuns(Map<Integer, RunEdit> runs) {
        return new PageEdits(runs, List.of());
    }

    public boolean isEmpty() {
        return runs.isEmpty() && added.isEmpty();
    }
}
