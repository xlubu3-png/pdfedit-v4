package com.pdfedit.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.junit.jupiter.api.Test;

class FontMatcherTest {

    @Test
    void guessesTheFamilyFromTheEmbeddedFontName() {
        assertThat(FontMatcher.guess("INPILL+HCRBatang-Bold", null).cssFamily).isEqualTo("Batang");
        assertThat(FontMatcher.guess("INPILL+HCRBatang-Bold", null).bold).isTrue();
        assertThat(FontMatcher.guess("AAAAAA+MalgunGothic", null).cssFamily).isEqualTo("맑은 고딕");
        assertThat(FontMatcher.guess("ABCDEF+NanumMyeongjo", null).cssFamily).isEqualTo("나눔명조");
        assertThat(FontMatcher.guess("ABCDEF+휴먼명조", null).cssFamily).isEqualTo("휴먼명조");
        assertThat(FontMatcher.guess("ABCDEF+HY신명조", null).cssFamily).isEqualTo("나눔명조");
        assertThat(FontMatcher.guess("ABCDEF+Unknown", null).cssFamily).isEqualTo("맑은 고딕");
        assertThat(FontMatcher.guess(null, null).cssFamily).isEqualTo("맑은 고딕");
    }

    @Test
    void failsWithAReadableMessageWhenNoFontIsInstalled() throws IOException {
        FontMatcher matcher = new FontMatcher(new SystemFonts(List.of()));

        assertThatThrownBy(() -> matcher.checkAvailable("AAAAAA+MalgunGothic", null))
                .isInstanceOf(FontUnavailableException.class)
                .hasMessageContaining("한글 글꼴");
        try (PDDocument doc = new PDDocument()) {
            assertThatThrownBy(() -> matcher.loadForExport(doc, "AAAAAA+MalgunGothic", null))
                    .isInstanceOf(FontUnavailableException.class);
        }
    }

    @Test
    void loadsAnInstalledFontForAnyKoreanFamily() throws IOException {
        FontMatcher matcher = new FontMatcher();
        assumeTrue(SystemFonts.system().find(false, "malgungothic", "nanumgothic", "dotum", "gulim").isPresent(),
                "no Korean font installed on this machine");

        try (PDDocument doc = new PDDocument()) {
            for (String name : List.of("AAAAAA+MalgunGothic", "AAAAAA+HCRBatang", "AAAAAA+Unknown")) {
                matcher.checkAvailable(name, null);
                PDFont font = matcher.loadForExport(doc, name, null);
                assertThat(font.getStringWidth("가")).isPositive();
            }
        }
    }
}
