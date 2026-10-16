package com.pdfedit.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
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
    void lettersTypedInTheStandInAreBroughtToTheSizeOfThePagesOwn() throws IOException {
        FontMatcher matcher = new FontMatcher();
        try (PDDocument doc = Loader.loadPDF(TestPdfs.type3Line("ABAB"))) {
            List<TextRun> runs = TextExtraction.extractRuns(doc, 0);
            RunPainter.PageGlyphs page = RunPainter.PageGlyphs.of(runs);
            PDFont source = runs.get(0).glyphs().get(0).font();

            float ratio = matcher.glyphSizeRatio(source, page, matcher.matchFor(source, page));

            assertThat(ratio).as("never more than a modest correction either way").isBetween(0.7f, 1.1f);
        }
    }

    @Test
    void aFontFoundByNameIsNotJudgedAndHasNoSimilarity() throws IOException {
        PageTextDto page = service.describePage(TestPdfs.koreanPage(), 0, Map.of());

        assertThat(page.runs()).isNotEmpty().allMatch(run -> run.matchSimilarity() == null);
    }
}
