package com.pdfedit.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Two marks the reader gets wrong are put right: ‰ read as "%。", and "。" ending Korean text or a number. */
class MarksTest {

    private static String fixed(String read) {
        StringBuilder text = new StringBuilder(read);
        List<Float> centers = new ArrayList<>();
        for (int i = 0; i < read.length(); i++) {
            centers.add((float) i);
        }
        OcrEngine.marks(text, centers);
        assertThat(centers).hasSize(text.length());
        return text.toString();
    }

    @Test
    void perMilleAndFullStops() {
        assertThat(fixed("혈중알코올농도 0.03%。 이상")).isEqualTo("혈중알코올농도 0.03‰ 이상");
        assertThat(fixed("제출하여야 한다。")).isEqualTo("제출하여야 한다.");
        assertThat(fixed("2026。 11。 28。")).isEqualTo("2026. 11. 28.");
        // At the head of a line it is a bullet; after Latin letters it is left as read.
        assertThat(fixed("。 항목")).isEqualTo("。 항목");
        assertThat(fixed("ABC。")).isEqualTo("ABC。");
    }
}
