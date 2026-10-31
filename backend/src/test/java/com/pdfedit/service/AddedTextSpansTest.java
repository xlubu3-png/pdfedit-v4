package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pdfedit.text.AddedText;
import com.pdfedit.text.FontMatcher;
import com.pdfedit.text.RunEdit.Span;

/** A text box with parts of its text bold, coloured, resized or underlined: what a copied formatted line becomes. */
class AddedTextSpansTest {

    private final TextEditService service = new TextEditService(new FontMatcher());

    private static AddedText box(String text, Span... spans) {
        return new AddedText("a1", text, 100f, 600f, null, 24f, false, "#000000", List.of(spans));
    }

    private BufferedImage render(AddedText box) throws IOException {
        byte[] page = service.buildSinglePage(TestPdfs.blankPage(), 0, new PageEdits(Map.of(), List.of(box)));
        return TestPdfs.render(page);
    }

    private static int count(BufferedImage image, int fromRow, int toRow, int fromCol, int toCol, Predicate<Color> kind) {
        int n = 0;
        for (int y = Math.max(0, fromRow); y < Math.min(image.getHeight(), toRow); y++) {
            for (int x = Math.max(0, fromCol); x < Math.min(image.getWidth(), toCol); x++) {
                if (kind.test(new Color(image.getRGB(x, y)))) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean red(Color c) {
        return c.getRed() > 150 && c.getGreen() < 90 && c.getBlue() < 90;
    }

    private static boolean ink(Color c) {
        return c.getRed() < 160 || c.getGreen() < 160 || c.getBlue() < 160;
    }

    /** The column in the middle of the first gap of 8 or more empty columns after the first ink: between two words. */
    private static int gapBetweenWords(BufferedImage image) {
        int run = 0;
        boolean started = false;
        for (int x = 0; x < image.getWidth(); x++) {
            boolean empty = count(image, 0, image.getHeight(), x, x + 1, AddedTextSpansTest::ink) == 0;
            if (!empty) {
                started = true;
                run = 0;
            } else if (started && ++run >= 8) {
                return x - run / 2;
            }
        }
        throw new AssertionError("no gap between words");
    }

    @Test
    void aColourOnPartOfTheTextStaysOnThoseLetters() throws IOException {
        int gap = gapBetweenWords(render(box("Hello World")));
        BufferedImage image = render(box("Hello World", new Span(6, 11, null, "#ff0000")));

        assertThat(count(image, 0, image.getHeight(), 0, gap, AddedTextSpansTest::red)).as("no red in Hello").isZero();
        assertThat(count(image, 0, image.getHeight(), gap, image.getWidth(), AddedTextSpansTest::red)).as("World is red")
                .isGreaterThan(100);
    }

    @Test
    void aSizeAndAnUnderlineOnPartOfTheTextShowOnThoseLettersOnly() throws IOException {
        BufferedImage plain = render(box("Hello World"));
        int gap = gapBetweenWords(plain);
        BufferedImage formatted = render(box("Hello World", new Span(6, 11, null, null, 48f, true)));

        int top = 2 * (842 - 600) - 2 * 48; // generous: the baseline is at 600
        int plainInk = count(plain, top, top + 2 * 70, gap, plain.getWidth(), AddedTextSpansTest::ink);
        int bigInk = count(formatted, top, top + 2 * 70, gap, formatted.getWidth(), AddedTextSpansTest::ink);
        assertThat(bigInk).as("World is bigger").isGreaterThan(plainInk * 2);
        int beneath = 2 * (842 - 600) + 2 * 3;
        assertThat(count(formatted, beneath, beneath + 8, gap, formatted.getWidth(), AddedTextSpansTest::ink))
                .as("a line beneath it").isGreaterThan(100);
        assertThat(count(formatted, beneath, beneath + 8, 0, gap, AddedTextSpansTest::ink)).as("none beneath Hello")
                .isZero();
    }

    @Test
    void theSecondLineTakesItsOwnPartsFromThePositionsOfTheWholeText() throws IOException {
        // "ab\nWorld": the red part is characters 3 to 8, all on the second line
        BufferedImage image = render(box("ab\nWorld", new Span(3, 8, null, "#ff0000")));
        int firstLineRows = 2 * (842 - 600) - 2 * 3;
        assertThat(count(image, 0, firstLineRows, 0, image.getWidth(), AddedTextSpansTest::red)).as("none on line one")
                .isZero();
        assertThat(count(image, firstLineRows, image.getHeight(), 0, image.getWidth(), AddedTextSpansTest::red))
                .as("red on line two").isGreaterThan(100);
    }

    @Test
    void theSpansSurviveARestartAndComeBackWithThePage(@TempDir Path folder) throws IOException {
        PdfDocumentStore first = new PdfDocumentStore(folder, Duration.ofDays(7));
        String id = first.store("blank.pdf", TestPdfs.blankPage(), 1);
        AddedText saved = box("Hello World", new Span(0, 5, true, "#0000ff", 30f, true));
        first.setAddedTexts(id, 0, List.of(saved));

        PageEdits edits = new PdfDocumentStore(folder, Duration.ofDays(7)).pageEditsSnapshot(id, 0);

        assertThat(edits.added()).containsExactly(saved);
        assertThat(service.describePage(TestPdfs.blankPage(), 0, edits).added().get(0).spans()).hasSize(1);
    }

    @Test
    void aBoxStoredWithoutSpansStillLoads() {
        AddedText old = new AddedText("a", "x", 1f, 1f, null, 12f, false, null);
        assertThat(old.spans()).isEmpty();
    }

    /** The browser sends what it knows of a box: no turn and no cover for one typed in. */
    @Test
    void aBoxSentWithoutATurnOrCoverIsReadAsUpright() {
        String sent = "{\"id\":\"a\",\"text\":\"x\",\"x\":1,\"y\":2,\"fontFamily\":null,\"fontSize\":12,\"bold\":false,"
                + "\"color\":null,\"spans\":[]}";

        AddedText box = tools.jackson.databind.json.JsonMapper.builder().build().readValue(sent, AddedText.class);

        assertThat(box.rotation()).isZero();
        assertThat(box.cover()).isNull();
        assertThat(box.y()).isEqualTo(2f);
    }

    @Test
    void nonsenseInSpansIsRefused() throws IOException {
        byte[] blank = TestPdfs.blankPage();
        assertThatThrownBy(() -> service.checkAdded(blank, 0, List.of(box("Hello", new Span(0, 3, null, "red")))))
                .isInstanceOf(InvalidEditException.class);
        assertThatThrownBy(() -> service.checkAdded(blank, 0, List.of(box("Hello", new Span(0, 3, null, null, 0f, null)))))
                .isInstanceOf(InvalidEditException.class);
        assertThat(service.checkAdded(blank, 0, List.of(box("Hello", new Span(0, 3, true, "#ff0000"))))).hasSize(1);
    }
}
