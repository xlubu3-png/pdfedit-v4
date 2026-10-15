package com.pdfedit.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pdfedit.dto.PageTextDto;
import com.pdfedit.dto.TextRunDto;
import com.pdfedit.service.TestPdfs;
import com.pdfedit.service.TextEditService;

/** A font that says nothing about itself is matched against every installed Korean font. */
class TypefaceChoiceTest {

    private final TextEditService service = new TextEditService(new FontMatcher());

    @Test
    void anotherFontHasToBeClearlyBetterBeforeItReplacesTheStockChoice() {
        assertThat(TypefaceChooser.beats(0.916, 0.837)).isTrue();
        assertThat(TypefaceChooser.beats(0.85, 0.837)).as("a hair is not worth a different font").isFalse();
        assertThat(TypefaceChooser.beats(0.837, 0.837)).isFalse();
    }

    @Test
    void aType3LineSaysHowCloselyItsFontWasMatched() throws IOException {
        PageTextDto page = service.describePage(TestPdfs.type3Line("ABAB"), 0, Map.of());

        TextRunDto run = page.runs().get(0);

        assertThat(run.matchSimilarity()).isNotNull().isBetween(0f, 1f);
        assertThat(run.fontFamily()).isNotBlank();
    }

    @Test
    void aFontFoundByNameIsNotJudgedAndHasNoSimilarity() throws IOException {
        PageTextDto page = service.describePage(TestPdfs.koreanPage(), 0, Map.of());

        assertThat(page.runs()).isNotEmpty().allMatch(run -> run.matchSimilarity() == null);
    }
}
