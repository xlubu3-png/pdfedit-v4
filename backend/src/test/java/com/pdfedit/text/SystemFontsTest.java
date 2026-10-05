package com.pdfedit.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemFontsTest {

    @Test
    void emptyOrMissingFolderYieldsNoFonts(@TempDir Path empty) throws IOException {
        Files.writeString(empty.resolve("readme.txt"), "not a font");
        Files.writeString(empty.resolve("broken.ttf"), "not a real font either");

        SystemFonts fonts = new SystemFonts(List.of(empty, empty.resolve("does-not-exist")));

        assertThat(fonts.find(false, "malgungothic")).isEmpty();
        assertThat(fonts.findContaining(false, "gothic")).isEmpty();
    }

    @Test
    void extraFontFoldersComeFromASettingSeparatedLikePath() {
        String setting = "/host-fonts" + java.io.File.pathSeparator + " " + java.io.File.pathSeparator
                + "/more fonts";

        assertThat(SystemFonts.extraRoots(setting)).containsExactly(Path.of("/host-fonts"), Path.of("/more fonts"));
        assertThat(SystemFonts.extraRoots(null)).isEmpty();
    }

    @Test
    void findsAnInstalledKoreanFontByItsInternalName() {
        Optional<SystemFonts.Face> sans = SystemFonts.system().find(false, "malgungothic", "nanumgothic");
        assumeTrue(sans.isPresent(), "no Korean sans font installed on this machine");

        assertThat(sans.get().names()).anyMatch(n -> n.equals("malgungothic") || n.equals("nanumgothic"));
        assertThat(sans.get().bold()).isFalse();
    }

    @Test
    void embedsAFontFromATrueTypeCollectionAndDrawsEveryCharacterCorrectly() throws IOException {
        Optional<SystemFonts.Face> batang = SystemFonts.system().find(false, "batang");
        assumeTrue(batang.isPresent() && batang.get().inCollection(), "Batang (batang.ttc) is not installed");

        try (PDDocument doc = new PDDocument()) {
            PDFont font = batang.get().embed(doc);
            byte[] pdf = drawLines(doc, font, LINES);

            try (PDDocument saved = Loader.loadPDF(pdf)) {
                assertThat(inkPixels(new PDFRenderer(saved).renderImage(0, 1f))).as("glyphs were drawn")
                        .isGreaterThan(500);
                assertThat(new PDFTextStripper().getText(saved).split("\\R")).containsExactly(LINES);
            }
        }
    }

    /**
     * One font instance is measured and drawn from many places (the per-export font cache shares
     * it between edits). Embedding only the used glyphs must render exactly like embedding the whole
     * font - an older PDFBox collapsed unrelated characters onto one glyph in this situation.
     */
    @Test
    void subsetEmbeddingRendersExactlyLikeFullEmbedding() throws IOException {
        Optional<SystemFonts.Face> sans = SystemFonts.system().find(false, "malgungothic", "nanumgothic")
                .filter(face -> !face.inCollection());
        assumeTrue(sans.isPresent(), "no standalone Korean font installed on this machine");

        byte[] subset;
        byte[] full;
        try (PDDocument doc = new PDDocument()) {
            subset = drawLines(doc, sans.get().embed(doc, true), LINES);
        }
        try (PDDocument doc = new PDDocument()) {
            full = drawLines(doc, sans.get().embed(doc, false), LINES);
        }

        try (PDDocument a = Loader.loadPDF(subset); PDDocument b = Loader.loadPDF(full)) {
            BufferedImage subsetImage = new PDFRenderer(a).renderImage(0, 2f);
            BufferedImage fullImage = new PDFRenderer(b).renderImage(0, 2f);
            int differing = 0;
            for (int y = 0; y < subsetImage.getHeight(); y++) {
                for (int x = 0; x < subsetImage.getWidth(); x++) {
                    if (subsetImage.getRGB(x, y) != fullImage.getRGB(x, y)) {
                        differing++;
                    }
                }
            }
            assertThat(differing).isZero();
            assertThat(new PDFTextStripper().getText(a).split("\\R")).containsExactly(LINES);
        }
        assertThat(subset.length).as("subset is much smaller than the full font").isLessThan(full.length / 4);
    }

    private static final String[] LINES = {"가나다라마바사", "ABC abc 123", "한글 테스트 문장입니다"};

    /** Measures each line before drawing it, like the editor does, with a single shared font instance. */
    private static byte[] drawLines(PDDocument doc, PDFont font, String[] lines) throws IOException {
        PDPage page = new PDPage(new PDRectangle(300, 120));
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            float y = 90;
            for (String line : lines) {
                assertThat(font.getStringWidth(line)).isPositive();
                cs.beginText();
                cs.setFont(font, 16);
                cs.newLineAtOffset(10, y);
                cs.showText(line);
                cs.endText();
                y -= 30;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private static int inkPixels(BufferedImage image) {
        int ink = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFF) < 128) {
                    ink++;
                }
            }
        }
        return ink;
    }
}
