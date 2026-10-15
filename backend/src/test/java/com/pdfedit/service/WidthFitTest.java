package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import com.pdfedit.text.RunEdit;
import com.pdfedit.text.TextRun;

/** A line set in a stand-in font is squeezed or stretched to the room the original text took. */
class WidthFitTest {

    private static final PDFont FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final String TEXT = "The quick brown fox";
    private static final float SIZE = 12f;

    /** What {@link #TEXT} measures in the stand-in font at {@link #SIZE}. */
    private static float natural() throws IOException {
        return FONT.getStringWidth(TEXT) / 1000f * SIZE;
    }

    private static TextRun original(float width, float fontSize) {
        return new TextRun(50f, 700f, width, 10f, fontSize, TEXT, "ABCDEF+Whatever");
    }

    @Test
    void aLineThatWasAsWideAsTheStandInIsLeftAlone() throws IOException {
        float fit = TextEditService.widthFit(FONT, original(natural(), SIZE), RunEdit.ofText("x"), SIZE);

        assertThat(fit).isEqualTo(1f);
    }

    @Test
    void aNarrowerOriginalSqueezesTheStandIn() throws IOException {
        float fit = TextEditService.widthFit(FONT, original(natural() * 0.85f, SIZE), RunEdit.ofText("x"), SIZE);

        assertThat(fit).isCloseTo(0.85f, within(0.01f));
    }

    @Test
    void aWiderOriginalStretchesTheStandIn() throws IOException {
        float fit = TextEditService.widthFit(FONT, original(natural() * 1.1f, SIZE), RunEdit.ofText("x"), SIZE);

        assertThat(fit).isCloseTo(1.1f, within(0.01f));
    }

    @Test
    void theSqueezeAndTheStretchHaveLimits() throws IOException {
        assertThat(TextEditService.widthFit(FONT, original(natural() * 0.3f, SIZE), RunEdit.ofText("x"), SIZE))
                .isEqualTo(0.7f);
        assertThat(TextEditService.widthFit(FONT, original(natural() * 3f, SIZE), RunEdit.ofText("x"), SIZE))
                .isEqualTo(1.15f);
    }

    @Test
    void aFontPickedByTheUserIsDrawnAsItIs() throws IOException {
        RunEdit picked = new RunEdit("x", "Batang", null, null, null, 0f, 0f);

        float fit = TextEditService.widthFit(FONT, original(natural() * 0.8f, SIZE), picked, SIZE);

        assertThat(fit).isEqualTo(1f);
    }

    @Test
    void aBiggerSizeKeepsTheSameProportion() throws IOException {
        // the original was 12pt and 15% narrower than the stand-in; at 24pt the target doubles with the text
        TextRun narrow = original(natural() * 0.85f, 12f);

        float fit = TextEditService.widthFit(FONT, narrow, RunEdit.ofText("x"), 24f);

        assertThat(fit).isCloseTo(0.85f, within(0.01f));
    }

    @Test
    void anOriginalThatCannotBeMeasuredIsLeftAlone() {
        TextRun empty = new TextRun(50f, 700f, 0f, 10f, 12f, TEXT, null);

        assertThat(TextEditService.widthFit(FONT, empty, RunEdit.ofText("x"), SIZE)).isEqualTo(1f);
    }
}
