package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import com.pdfedit.dto.TextRunDto;
import com.pdfedit.text.FontMatcher;
import com.pdfedit.text.RunEdit;
import com.pdfedit.text.RunEdit.Span;

/** Part of a line bold or in another colour: only those letters change, the rest of the line stays as it was. */
class PartialFormatTest {

    private final TextEditService service = new TextEditService(new FontMatcher());

    private static byte[] helloWorld() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                cs.newLineAtOffset(50, 700);
                cs.showText("Hello World");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static boolean isRed(Color c) {
        return c.getRed() > 150 && c.getGreen() < 90 && c.getBlue() < 90;
    }

    private static boolean isInk(Color c) {
        return c.getRed() < 160 || c.getGreen() < 160 || c.getBlue() < 160;
    }

    /** Pixels matching {@code kind} between columns {@code from} and {@code to} (2x page pixels). */
    private static int count(BufferedImage image, int from, int to, Predicate<Color> kind) {
        int n = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = Math.max(0, from); x < Math.min(image.getWidth(), to); x++) {
                if (kind.test(new Color(image.getRGB(x, y)))) {
                    n++;
                }
            }
        }
        return n;
    }

    private byte[] bake(byte[] pdf, RunEdit edit) throws IOException {
        TextRunDto run = service.describePage(pdf, 0, Map.of()).runs().get(0);
        return service.buildSinglePage(pdf, 0, Map.of(run.index(), edit));
    }

    @Test
    void aColourOnPartOfTheLineStaysOnThoseLetters() throws IOException {
        byte[] baked = bake(helloWorld(), new RunEdit("Hello World", null, null, null, null, 0f, 0f,
                List.of(new Span(6, 11, null, "#ff0000"))));
        BufferedImage image = TestPdfs.render(baked);

        // "Hello " is about 61pt wide at 24pt; at 2x the red word starts right of x = 2 * (50 + 55)
        assertThat(count(image, 0, 205, PartialFormatTest::isRed)).as("no red in Hello").isZero();
        assertThat(count(image, 215, image.getWidth(), PartialFormatTest::isRed)).as("World is red").isGreaterThan(80);
        assertThat(count(image, 0, 205, PartialFormatTest::isInk)).as("Hello is drawn").isGreaterThan(80);
    }

    @Test
    void boldOnPartOfTheLineThickensOnlyThoseLetters() throws IOException {
        byte[] pdf = helloWorld();
        BufferedImage plain = TestPdfs.render(bake(pdf, new RunEdit("Hello World", null, null, false, null, 0f, 0f)));
        BufferedImage partial = TestPdfs.render(bake(pdf, new RunEdit("Hello World", null, null, false, null, 0f, 0f,
                List.of(new Span(6, 11, true, null)))));

        int helloPlain = count(plain, 0, 200, PartialFormatTest::isInk);
        int helloPartial = count(partial, 0, 200, PartialFormatTest::isInk);
        assertThat(helloPartial).as("Hello unchanged")
                .isBetween(helloPlain - helloPlain / 20, helloPlain + helloPlain / 20);

        int worldPlain = count(plain, 235, plain.getWidth(), PartialFormatTest::isInk);
        int worldPartial = count(partial, 235, partial.getWidth(), PartialFormatTest::isInk);
        assertThat(worldPartial).as("World is heavier").isGreaterThan(worldPlain + worldPlain / 10);
    }

    @Test
    void theSpansComeBackWithTheRunAfterTheyAreRecorded() throws IOException {
        byte[] pdf = helloWorld();
        TextRunDto run = service.describePage(pdf, 0, Map.of()).runs().get(0);
        RunEdit edit = new RunEdit("Hello World", null, null, null, null, 0f, 0f,
                List.of(new Span(0, 5, true, "#0000ff")));
        TextRunDto after = service.describePage(pdf, 0, Map.of(run.index(), edit)).runs().get(0);
        assertThat(after.editSpans()).containsExactly(new Span(0, 5, true, "#0000ff"));
        assertThat(service.describePage(pdf, 0, Map.of()).runs().get(0).editSpans()).isEmpty();
    }
}
