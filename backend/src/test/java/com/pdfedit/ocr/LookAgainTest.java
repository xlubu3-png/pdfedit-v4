package com.pdfedit.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A page is read again with the detector given it larger only when many of the lines found were too unsure. */
class LookAgainTest {

    @Test
    void manyUnsureLinesOnAPageAreLookedForAgain() {
        assertThat(OcrEngine.lookAgain(20, 80, 2339)).isTrue();
    }

    @Test
    void aFewUnsureLinesOrAFewInAManyAreNot() {
        assertThat(OcrEngine.lookAgain(14, 10, 2339)).isFalse();
        assertThat(OcrEngine.lookAgain(20, 200, 2339)).isFalse();
    }

    @Test
    void aPictureAlreadyLargeIsNotEnlargedFurther() {
        assertThat(OcrEngine.lookAgain(40, 40, 4000)).isFalse();
    }

    @Test
    void theSecondReadingWinsOnlyWhenItsLinesAreSurerTakenTogetherAndNoMoreWereLeftOut() {
        assertThat(OcrEngine.isBetter(92.0, 6, 78.5, 20)).isTrue();
        // More lines but shakier ones, or more left out than before: the first reading stays.
        assertThat(OcrEngine.isBetter(70.0, 4, 78.5, 20)).isFalse();
        assertThat(OcrEngine.isBetter(92.0, 25, 78.5, 20)).isFalse();
    }
}
