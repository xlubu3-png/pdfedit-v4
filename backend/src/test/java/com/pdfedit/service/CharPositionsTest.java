package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.pdfedit.text.Glyph;
import com.pdfedit.text.TextRun;

/** Where each character of a line starts, so a line over a table's column border can be split when copied. */
class CharPositionsTest {

    private static Glyph glyph(String unicode, float x) {
        return new Glyph(unicode, null, 0, x, 10, 1, 0, 1, null, null);
    }

    @Test
    void eachCharacterStartsWhereItsGlyphDoesAndAnAddedSpaceWhereTheNextGlyphDoes() {
        TextRun run = new TextRun(10, 0, 100, 10, 10, "AB C", null, null,
                List.of(glyph("A", 10), glyph("B", 16), glyph("C", 60)));

        assertThat(TextEditService.charPositions(run)).containsExactly(10f, 16f, 60f, 60f);
    }

    @Test
    void unknownWhenTheCharactersWereNotDescribedOrDoNotMatch() {
        assertThat(TextEditService.charPositions(new TextRun(0, 0, 10, 10, 10, "AB", null))).isNull();
        TextRun mismatched = new TextRun(0, 0, 10, 10, 10, "AB", null, null, List.of(glyph("X", 0), glyph("Y", 5)));
        assertThat(TextEditService.charPositions(mismatched)).isNull();
    }
}
