package com.pdfedit.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A well-known Latin font is taken at the weight its name says, not judged by its glyphs against Korean fonts. */
class LatinFontWeightTest {

    @Test
    void wellKnownLatinFamiliesAreToldByName() {
        assertThat(FontMatcher.latinFamily("Helvetica")).isEqualTo("helvetica");
        assertThat(FontMatcher.latinFamily("ABCDEF+Arial-BoldMT")).isEqualTo("arial");
        assertThat(FontMatcher.latinFamily("TimesNewRomanPSMT")).isEqualTo("times");
        assertThat(FontMatcher.latinFamily("LiberationSerif-Regular")).startsWith("liberationserif");
        assertThat(FontMatcher.latinFamily("HYwulM")).isNull();
        assertThat(FontMatcher.latinFamily("T1")).isNull();
    }
}
