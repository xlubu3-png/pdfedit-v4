package com.pdfedit.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Another reading of a line takes the best reading's spaces when it reads as many letters. */
class LaidOutLikeTest {

    @Test
    void anotherReadingTakesTheSpacesOfTheBestWhenItHasAsManyLetters() {
        assertThat(OcrEngine.laidOutLike("합계 240,070 원", "합계240.070원")).isEqualTo("합계 240.070 원");
        assertThat(OcrEngine.laidOutLike("합계 240,070 원", "합계24070원")).isEqualTo("합계24070원");
    }
}
