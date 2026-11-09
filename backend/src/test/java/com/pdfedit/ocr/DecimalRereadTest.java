package com.pdfedit.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A number read again for its lost decimal point is taken only when it is the same number with the point found. */
class DecimalRereadTest {

    @Test
    void theSameDigitsWithTheirPointAreTaken() {
        assertThat(OcrEngine.withDecimal("121", "12.1")).isEqualTo("12.1");
        assertThat(OcrEngine.withDecimal("1250", " 12.50 ")).isEqualTo("12.50");
        assertThat(OcrEngine.withDecimal("31", "3.11")).isEqualTo("3.11");
    }

    @Test
    void anotherNumberOrNoPointIsNot() {
        assertThat(OcrEngine.withDecimal("121", "13.1")).isNull();
        assertThat(OcrEngine.withDecimal("121", "121")).isNull();
        assertThat(OcrEngine.withDecimal("121", "1.2134")).isNull();
        assertThat(OcrEngine.withDecimal("121", "12.1kg")).isNull();
    }
}
