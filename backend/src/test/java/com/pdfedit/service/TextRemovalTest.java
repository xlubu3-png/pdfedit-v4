package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import com.pdfedit.dto.PageTextDto;
import com.pdfedit.dto.TextRunDto;
import com.pdfedit.text.FontMatcher;
import com.pdfedit.text.RunEdit;
import com.pdfedit.text.TextExtraction;

/**
 * An edited line's original letters are cut out of the page's content, not painted over - and only when
 * that is provably harmless.
 */
class TextRemovalTest {

    private static final PDType1Font HELVETICA = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private final TextEditService service = new TextEditService(new FontMatcher());

    private interface Drawing {
        void draw(PDDocument doc, PDPage page, PDPageContentStream cs) throws IOException;
    }

    private static byte[] pdf(Drawing drawing) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                drawing.draw(doc, page, cs);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void line(PDPageContentStream cs, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(HELVETICA, 12);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private static TextRunDto run(PageTextDto page, String startsWith) {
        return page.runs().stream().filter(r -> r.text().startsWith(startsWith)).findFirst().orElseThrow();
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private PageTextDto describe(byte[] pdf, Map<Integer, RunEdit> edits) throws IOException {
        return service.describePage(pdf, 0, edits);
    }

    @Test
    void anEditedLinesOriginalLettersAreCutOutAndTheOtherLinesStay() throws IOException {
        byte[] pdf = pdf((doc, page, cs) -> {
            line(cs, 50, 700, "Original heading");
            line(cs, 50, 650, "Keep this line");
        });
        int index = run(describe(pdf, Map.of()), "Original").index();
        Map<Integer, RunEdit> edits = Map.of(index, RunEdit.ofText("Changed heading"));

        assertThat(run(describe(pdf, edits), "Original").originalRemoved()).isTrue();
        String text = textOf(service.buildSinglePage(pdf, 0, edits));

        assertThat(text).doesNotContain("Original").contains("Keep this line");
    }

    @Test
    void aLineThatIsNotEditedIsNeverReportedAsCutOut() throws IOException {
        byte[] pdf = pdf((doc, page, cs) -> line(cs, 50, 700, "Plain line"));

        assertThat(describe(pdf, Map.of()).runs()).noneMatch(TextRunDto::originalRemoved);
    }

    @Test
    void theTextAfterACutLineKeepsItsPlace() throws IOException {
        // "Left" and "Right" are two operators in one text object: Right starts where Left's advance ends.
        byte[] pdf = pdf((doc, page, cs) -> {
            cs.beginText();
            cs.setFont(HELVETICA, 12);
            cs.newLineAtOffset(50, 700);
            cs.showText("Left");
            cs.appendRawCommands("[-20000] TJ\n");
            cs.showText("Right");
            cs.endText();
        });
        PageTextDto before = describe(pdf, Map.of());
        TextRunDto left = run(before, "Left");
        TextRunDto right = run(before, "Right");
        Map<Integer, RunEdit> edits = Map.of(left.index(), RunEdit.ofText("Gone"));

        assertThat(run(describe(pdf, edits), "Left").originalRemoved()).isTrue();
        byte[] baked = service.buildSinglePage(pdf, 0, edits);

        try (PDDocument doc = Loader.loadPDF(baked)) {
            var after = TextExtraction.extractRuns(doc, 0);
            var stay = after.stream().filter(r -> r.text().startsWith("Right")).findFirst().orElseThrow();
            assertThat(stay.x()).isEqualTo(right.x(), org.assertj.core.data.Offset.offset(0.5f));
            assertThat(after).noneMatch(r -> r.text().startsWith("Left"));
        }
    }

    @Test
    void textInsideAFormIsCutOutOfACopyAndTheSourceIsLeftAlone() throws IOException {
        byte[] pdf = pdf((doc, page, cs) -> {
            PDAppearanceStream form = new PDAppearanceStream(doc);
            form.setBBox(PDRectangle.A4);
            form.setResources(new PDResources());
            try (PDPageContentStream formCs = new PDPageContentStream(doc, form)) {
                line(formCs, 50, 700, "Inside the form");
            }
            cs.drawForm(form);
            line(cs, 50, 650, "Outside the form");
        });
        int index = run(describe(pdf, Map.of()), "Inside").index();
        Map<Integer, RunEdit> edits = Map.of(index, RunEdit.ofText("Replaced"));

        assertThat(run(describe(pdf, edits), "Inside").originalRemoved()).isTrue();
        String text = textOf(service.buildSinglePage(pdf, 0, edits));

        assertThat(text).doesNotContain("Inside").contains("Outside the form");
        assertThat(textOf(pdf)).as("the source document is not changed by an edit").contains("Inside the form");
    }

    @Test
    void aFormDrawnTwiceCannotBeCutForOnePlaceSoItIsCoveredInstead() throws IOException {
        byte[] pdf = pdf((doc, page, cs) -> {
            PDAppearanceStream form = new PDAppearanceStream(doc);
            form.setBBox(PDRectangle.A4);
            form.setResources(new PDResources());
            try (PDPageContentStream formCs = new PDPageContentStream(doc, form)) {
                line(formCs, 50, 700, "Stamp text");
            }
            cs.drawForm(form);
            cs.saveGraphicsState();
            cs.transform(org.apache.pdfbox.util.Matrix.getTranslateInstance(0, -200));
            cs.drawForm(form);
            cs.restoreGraphicsState();
        });
        PageTextDto page = describe(pdf, Map.of());
        int index = page.runs().get(0).index();

        PageTextDto edited = describe(pdf, Map.of(index, RunEdit.ofText("Other")));

        assertThat(edited.runs().get(0).originalRemoved()).isFalse();
        // still shown correctly: covered, and the original is still in the file
        assertThat(textOf(service.buildSinglePage(pdf, 0, Map.of(index, RunEdit.ofText("Other"))))).contains("Stamp text");
    }

    @Test
    void anOperatorThatAlsoDrawsALineThatStaysIsNotCut() throws IOException {
        // one TJ draws two pieces far apart: editing one must not take the other with it
        byte[] pdf = pdf((doc, page, cs) -> {
            cs.beginText();
            cs.setFont(HELVETICA, 12);
            cs.newLineAtOffset(50, 700);
            cs.appendRawCommands("[(Left) -20000 (Right)] TJ\n");
            cs.endText();
        });
        PageTextDto page = describe(pdf, Map.of());
        List<TextRunDto> runs = page.runs();
        assertThat(runs).as("the piece in front and the piece behind the gap").hasSizeGreaterThanOrEqualTo(2);
        TextRunDto left = runs.stream().filter(r -> r.text().startsWith("Left")).findFirst().orElseThrow();

        PageTextDto edited = describe(pdf, Map.of(left.index(), RunEdit.ofText("Gone")));

        assertThat(run(edited, "Left").originalRemoved()).isFalse();
        String text = textOf(service.buildSinglePage(pdf, 0, Map.of(left.index(), RunEdit.ofText("Gone"))));
        assertThat(text).contains("Right");
    }
}
