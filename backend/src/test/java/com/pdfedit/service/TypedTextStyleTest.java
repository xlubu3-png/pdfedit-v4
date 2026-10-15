package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

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

/** Letters typed after a line take the look of the line's letters, not of a blank that happens to end it. */
class TypedTextStyleTest {

    private final TextEditService service = new TextEditService(new FontMatcher());

    /** "Hello" in black followed by a blue space - as a producer may emit the start of the next piece of text. */
    private static byte[] blackLineWithBlueTrailingSpace() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                cs.newLineAtOffset(50, 700);
                cs.setNonStrokingColor(Color.BLACK);
                cs.showText("Hello");
                cs.setNonStrokingColor(Color.BLUE);
                cs.showText(" ");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static int bluePixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                Color c = new Color(image.getRGB(x, y));
                if (c.getBlue() > 140 && c.getRed() < 90 && c.getGreen() < 90) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void textTypedAfterATrailingBlankKeepsTheColourOfTheLetters() throws IOException {
        byte[] pdf = blackLineWithBlueTrailingSpace();
        TextRunDto run = service.describePage(pdf, 0, Map.of()).runs().get(0);
        assertThat(run.text()).as("the run ends with the blank").endsWith(" ");

        byte[] baked = service.buildSinglePage(pdf, 0, Map.of(run.index(), RunEdit.ofText(run.text() + "World")));

        assertThat(bluePixels(TestPdfs.render(baked))).as("nothing typed turned blue").isZero();
    }

    @Test
    void theLineLooksTheSameWhenItIsNotTouched() throws IOException {
        // the blue space itself is invisible; the picture stays free of blue as well
        assertThat(bluePixels(TestPdfs.render(blackLineWithBlueTrailingSpace()))).isZero();
    }
}
