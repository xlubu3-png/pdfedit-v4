package com.pdfedit.service;

import java.util.List;
import java.util.Map;

import com.pdfedit.text.AddedImage;
import com.pdfedit.text.AddedText;
import com.pdfedit.text.RunEdit;

/**
 * Everything the user changed on one page: edits of existing text runs, and the text boxes and pictures
 * added to it.
 */
public record PageEdits(Map<Integer, RunEdit> runs, List<AddedText> added, List<PlacedImage> images) {

    /** A picture box together with the picture's bytes (as uploaded), ready to be drawn. */
    public record PlacedImage(AddedImage box, byte[] data) {
    }

    public static final PageEdits NONE = new PageEdits(Map.of(), List.of());

    public PageEdits(Map<Integer, RunEdit> runs, List<AddedText> added) {
        this(runs, added, List.of());
    }

    public static PageEdits ofRuns(Map<Integer, RunEdit> runs) {
        return new PageEdits(runs, List.of());
    }

    public boolean isEmpty() {
        return runs.isEmpty() && added.isEmpty() && images.isEmpty();
    }

    /** The picture boxes alone, as the browser keeps them. */
    public List<AddedImage> imageBoxes() {
        return images.stream().map(PlacedImage::box).toList();
    }
}
