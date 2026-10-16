package com.pdfedit.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.pdfedit.text.RunEdit.Segment;
import com.pdfedit.text.RunEdit.Span;

/** Partial formatting: spans follow the letters they were put on when the text around them is edited. */
class RunEditSpansTest {

    private static RunEdit edit(String text, Span... spans) {
        return new RunEdit(text, null, null, null, null, 0f, 0f, List.of(spans));
    }

    @Test
    void textTypedBeforeASpanShiftsIt() {
        RunEdit moved = edit("Hello World", new Span(6, 11, true, null)).withText("Oh Hello World");
        assertThat(moved.spans()).containsExactly(new Span(9, 14, true, null));
    }

    @Test
    void textTypedAfterASpanLeavesItAlone() {
        RunEdit same = edit("Hello World", new Span(0, 5, null, "#ff0000")).withText("Hello World!!");
        assertThat(same.spans()).containsExactly(new Span(0, 5, null, "#ff0000"));
    }

    @Test
    void textTypedInsideASpanJoinsIt() {
        RunEdit grown = edit("Hello World", new Span(6, 11, true, null)).withText("Hello Wonderful World");
        assertThat(grown.spans()).hasSize(1);
        assertThat(grown.spans().get(0).start()).isEqualTo(6);
        assertThat(grown.spans().get(0).end()).isEqualTo(21);
    }

    @Test
    void aSpanWhoseLettersAreAllReplacedCoversTheReplacement() {
        RunEdit replaced = edit("Hello World", new Span(6, 11, true, null)).withText("Hello Earth");
        assertThat(replaced.spans()).containsExactly(new Span(6, 11, true, null));
    }

    @Test
    void aSpanWhoseLettersAreDeletedDisappears() {
        RunEdit shorter = edit("Hello World", new Span(5, 11, true, null)).withText("Hello");
        assertThat(shorter.spans()).isEmpty();
    }

    @Test
    void cleanSpansClipsDropsAndOrders() {
        List<Span> clean = RunEdit.cleanSpans(List.of(
                new Span(8, 20, null, "#00ff00"), // runs past the end
                new Span(2, 2, true, null), // empty
                new Span(0, 3, null, null), // changes nothing
                new Span(-4, 1, true, null)), 10); // starts before the text
        assertThat(clean).containsExactly(new Span(0, 1, true, null), new Span(8, 10, null, "#00ff00"));
    }

    @Test
    void whereSpansOverlapTheLaterOneWins() {
        List<Span> clean = RunEdit.cleanSpans(List.of(new Span(0, 6, true, null), new Span(3, 9, null, "#ff0000")), 10);
        assertThat(clean).containsExactly(new Span(0, 3, true, null), new Span(3, 9, null, "#ff0000"));
    }

    @Test
    void segmentsCoverEveryCharacterOnce() {
        RunEdit e = edit("Hello World", new Span(2, 4, true, null), new Span(6, 11, null, "#0000ff"));
        assertThat(e.segments()).containsExactly(
                new Segment(0, 2, null, null),
                new Segment(2, 4, true, null),
                new Segment(4, 6, null, null),
                new Segment(6, 11, null, "#0000ff"));
    }

    @Test
    void aSpanMakesTheEditRestyled() {
        assertThat(RunEdit.ofText("a").restyled()).isFalse();
        assertThat(edit("abc", new Span(0, 1, true, null)).restyled()).isTrue();
    }
}
