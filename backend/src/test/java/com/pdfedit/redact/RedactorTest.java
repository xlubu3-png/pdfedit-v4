package com.pdfedit.redact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

import com.pdfedit.redact.PersonalInfoFinder.Kind;
import com.pdfedit.service.TestPdfs;

class RedactorTest {

    private static final PDType1Font HELVETICA = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    interface Drawing {
        void draw(PDDocument doc, PDPage page, PDPageContentStream cs) throws IOException;
    }

    static byte[] page(Drawing drawing) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                drawing.draw(doc, page, cs);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    static void line(PDPageContentStream cs, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(HELVETICA, 12);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private static List<Redaction> areas(List<PersonalInfoFinder.Match> matches) {
        return matches.stream().map(m -> new Redaction("r", m.x(), m.y(), m.width(), m.height())).toList();
    }

    /** The areas that redact what the finder finds for the term. */
    private static List<Redaction> areasOf(PDDocument doc, String term) throws IOException {
        return areas(PersonalInfoFinder.find(doc, 0, Set.of(), term));
    }

    private static float xOf(PDDocument doc, String term) throws IOException {
        return PersonalInfoFinder.find(doc, 0, Set.of(), term).get(0).x();
    }

    private static String text(PDDocument doc) throws IOException {
        return new PDFTextStripper().getText(doc);
    }

    /** Saves and loads again, so what is checked is the file as it is written. */
    private static PDDocument reloaded(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return Loader.loadPDF(out.toByteArray());
    }

    private static int rgbAt(PDDocument doc, int x, int yFromBottom) throws IOException {
        BufferedImage rendered = new PDFRenderer(doc).renderImageWithDPI(0, 72);
        return rendered.getRGB(x, rendered.getHeight() - yFromBottom) & 0xFFFFFF;
    }

    @Test
    void onlyTheLettersInsideTheAreaAreCutAndTheRestStaysWhereItWas() throws IOException {
        byte[] pdf = page((doc, page, cs) -> line(cs, 72, 700, "Phone 010-1234-5678 call me"));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            float callBefore = xOf(doc, "call");
            List<Redaction> areas = areas(PersonalInfoFinder.find(doc, 0, Set.of(Kind.MOBILE), null));
            assertThat(areas).hasSize(1);

            Redactor.Result result = Redactor.apply(doc, doc.getPage(0), areas);

            assertThat(result.rasterized()).isFalse();
            assertThat(result.glyphsRemoved()).isEqualTo("010-1234-5678".length());
            try (PDDocument saved = reloaded(doc)) {
                assertThat(text(saved)).contains("Phone").contains("call me").doesNotContain("010").doesNotContain("5678");
                assertThat(xOf(saved, "call")).isCloseTo(callBefore, within(0.01f));
                assertThat(Redactor.leftovers(saved, saved.getPage(0), areas)).isZero();
            }
        }
    }

    @Test
    void lettersOfAnEmbeddedKoreanFontAreCutToo() throws IOException {
        try (PDDocument doc = Loader.loadPDF(TestPdfs.koreanPage())) {
            Redactor.Result result = Redactor.apply(doc, doc.getPage(0), areasOf(doc, "세계"));

            assertThat(result.rasterized()).isFalse();
            try (PDDocument saved = reloaded(doc)) {
                assertThat(text(saved)).contains("안녕하세요").contains("Hello World").doesNotContain("세계");
            }
        }
    }

    @Test
    void aKernedLineKeepsItsSpacingAroundTheCut() throws IOException {
        byte[] pdf = page((doc, page, cs) -> {
            cs.beginText();
            cs.setFont(HELVETICA, 12);
            cs.newLineAtOffset(72, 700);
            cs.showTextWithPositioning(new Object[] {"Se", -20f, "cret", 100f, "Data"});
            cs.endText();
        });
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            float dataBefore = xOf(doc, "Data");

            Redactor.Result result = Redactor.apply(doc, doc.getPage(0), areasOf(doc, "cret"));

            assertThat(result.rasterized()).isFalse();
            try (PDDocument saved = reloaded(doc)) {
                assertThat(text(saved)).contains("Se").contains("Data").doesNotContain("cret");
                assertThat(xOf(saved, "Data")).isCloseTo(dataBefore, within(0.01f));
            }
        }
    }

    @Test
    void aPictureUnderAnAreaMakesThePageAPictureWithTheAreaBlackInIt() throws IOException {
        byte[] pdf = page((doc, page, cs) -> {
            BufferedImage white = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
            var g = white.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 100, 50);
            g.dispose();
            cs.drawImage(LosslessFactory.createFromImage(doc, white), 100, 500, 100, 50);
            line(cs, 72, 700, "Kept line");
        });
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Redactor.Result result = Redactor.apply(doc, doc.getPage(0), List.of(new Redaction("r", 120, 510, 40, 20)));

            assertThat(result.rasterized()).isTrue();
            assertThat(result.reason()).contains("그림");
            try (PDDocument saved = reloaded(doc)) {
                assertThat(text(saved).strip()).isEmpty();
                // The middle of the area is black; a point of the white picture outside it is not.
                assertThat(rgbAt(saved, 140, 520)).isZero();
                assertThat(rgbAt(saved, 110, 540)).isNotZero();
            }
        }
    }

    @Test
    void textInAFormDrawnTwiceMakesThePageAPicture() throws IOException {
        byte[] pdf = page((doc, page, cs) -> {
            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, 200, 30));
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), HELVETICA);
            form.setResources(resources);
            try (OutputStream out = form.getContentStream().createOutputStream()) {
                out.write("BT /F1 12 Tf 0 10 Td (Stamp 010-9999-8888) Tj ET".getBytes(StandardCharsets.US_ASCII));
            }
            for (float y : new float[] {700, 600}) {
                cs.saveGraphicsState();
                cs.transform(new Matrix(1, 0, 0, 1, 72, y));
                cs.drawForm(form);
                cs.restoreGraphicsState();
            }
        });
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            List<Redaction> areas = areas(PersonalInfoFinder.find(doc, 0, Set.of(Kind.MOBILE), null)).subList(0, 1);

            Redactor.Result result = Redactor.apply(doc, doc.getPage(0), areas);

            assertThat(result.rasterized()).isTrue();
            try (PDDocument saved = reloaded(doc)) {
                assertThat(text(saved)).doesNotContain("9999");
            }
        }
    }

    @Test
    void aLinkInsideAnAreaIsDroppedAndOneOutsideIsKept() throws IOException {
        byte[] pdf = page((doc, page, cs) -> {
            line(cs, 72, 700, "Secret");
            for (float y : new float[] {695, 400}) {
                PDAnnotationLink link = new PDAnnotationLink();
                link.setRectangle(new PDRectangle(72, y, 60, 20));
                page.getAnnotations().add(link);
            }
        });
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Redactor.Result result = Redactor.apply(doc, doc.getPage(0), areasOf(doc, "Secret"));

            assertThat(result.rasterized()).isFalse();
            assertThat(doc.getPage(0).getAnnotations()).hasSize(1);
            assertThat(doc.getPage(0).getAnnotations().get(0).getRectangle().getLowerLeftY()).isEqualTo(400f);
        }
    }

    @Test
    void anAreaOverNothingStillPaintsBlackAndChangesNoText() throws IOException {
        byte[] pdf = page((doc, page, cs) -> line(cs, 72, 700, "Untouched"));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Redactor.Result result = Redactor.apply(doc, doc.getPage(0), List.of(new Redaction("r", 300, 300, 50, 50)));

            assertThat(result.rasterized()).isFalse();
            assertThat(result.glyphsRemoved()).isZero();
            try (PDDocument saved = reloaded(doc)) {
                assertThat(text(saved)).contains("Untouched");
                assertThat(rgbAt(saved, 325, 325)).isZero();
            }
        }
    }
}
