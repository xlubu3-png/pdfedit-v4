package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import com.pdfedit.dto.PageTextDto;
import com.pdfedit.dto.TextEditItem;
import com.pdfedit.dto.TextRunDto;
import com.pdfedit.text.FontMatcher;
import com.pdfedit.text.RunEdit;

class TextEditServiceTest {

    private final TextEditService service = new TextEditService(new FontMatcher());

    @Test
    void describePageListsEachLineWithItsPositionAndFont() throws IOException {
        PageTextDto page = service.describePage(TestPdfs.koreanPage(), 0, Map.of());

        assertThat(page.rotated()).isFalse();
        assertThat(page.pageWidth()).isEqualTo(PDRectangle.A4.getWidth());
        assertThat(page.runs()).extracting(r -> squash(r.text())).containsExactly("안녕하세요세계", "HelloWorld");

        TextRunDto hello = run(page, "Hello World");
        assertThat(hello.x()).isCloseTo(50f, within(1f));
        assertThat(hello.y()).isCloseTo(650f, within(1f));
        assertThat(hello.fontSize()).isCloseTo(12f, within(0.5f));
        assertThat(hello.boxTop()).isGreaterThan(hello.y());
        assertThat(hello.boxBottom()).isLessThan(hello.y());
    }

    @Test
    void aFontWhoseNameSaysNothingIsJudgedByTheLookOfItsGlyphs() throws IOException {
        TextRunDto heavy = service.describePage(TestPdfs.nameLessSubsetLine("제5장 부록", true), 0, Map.of()).runs().get(0);
        TextRunDto light = service.describePage(TestPdfs.nameLessSubsetLine("제5장 부록", false), 0, Map.of()).runs().get(0);

        assertThat(heavy.sourceFont()).contains("HYwulM");
        assertThat(heavy.bold()).isTrue();
        assertThat(light.bold()).isFalse();
    }

    @Test
    void aRunReportsTheCharactersItsOwnFontDraws() throws IOException {
        TextRunDto run = service.describePage(TestPdfs.nameLessSubsetLine("제5장 부록", false), 0, Map.of()).runs().get(0);

        assertThat(run.ownChars()).contains("제", "5", "장", "부", "록").doesNotContain("가");
    }

    @Test
    void recordEditsKeepsChangedTextAndDropsRevertedOrOutOfRangeItems() throws IOException {
        byte[] pdf = TestPdfs.koreanPage();
        TextRunDto helloRun = run(service.describePage(pdf, 0, Map.of()), "Hello World");
        int hello = helloRun.index();
        Map<Integer, RunEdit> edits = new HashMap<>();

        service.recordEdits(pdf, 0, edits, List.of(new TextEditItem(hello, "Hold World"), new TextEditItem(999, "x")));
        assertThat(edits).containsOnlyKeys(hello);
        assertThat(edits.get(hello).text()).isEqualTo("Hold World");

        PageTextDto described = service.describePage(pdf, 0, edits);
        assertThat(run(described, "Hello World").edited()).isTrue();
        assertThat(run(described, "Hello World").currentText()).isEqualTo("Hold World");

        service.recordEdits(pdf, 0, edits, List.of(new TextEditItem(hello, helloRun.text())));
        assertThat(edits).isEmpty();
    }

    @Test
    void bakedEditReusesTheSourceFontWhenItIsFullyEmbedded() throws IOException {
        byte[] pdf = TestPdfs.koreanPage();
        // Edit inside a word and keep the rest of the line exactly as extracted: with some fonts the
        // space comes out of text extraction as '?', and typing a real space over it is a different
        // edit from the one this test is about.
        String extracted = run(service.describePage(pdf, 0, Map.of()), "Hello World").text();

        byte[] edited = bake(pdf, "Hello World", extracted.replace('e', 'o'));

        assertThat(TestPdfs.differingPixels(TestPdfs.render(edited),
                TestPdfs.render(TestPdfs.koreanPage("Hollo World")))).isLessThanOrEqualTo(TestPdfs.SUBPIXEL_NOISE);
        assertBakedEditStaysLocal(pdf, "Hello World", "Hold World");
    }

    @Test
    void bakedEditOnASubsetEmbeddedFontFallsBackToASubstituteFont() throws IOException {
        assertBakedEditStaysLocal(TestPdfs.subsetKoreanPage(), "Hello World", "Wyvern World");
    }

    @Test
    void koreanReplacementOnASubsetEmbeddedFontFallsBackToAFontWithHangul() throws IOException {
        assertBakedEditStaysLocal(TestPdfs.subsetKoreanPage(), "안녕하세요 세계", "한글수정");
    }

    @Test
    void pagesWithARotateEntryAreReportedAndRejected() throws IOException {
        byte[] pdf = TestPdfs.koreanPage(null, 90);

        PageTextDto page = service.describePage(pdf, 0, Map.of());
        assertThat(page.rotated()).isTrue();
        assertThat(page.runs()).isEmpty();
        assertThatThrownBy(() -> service.recordEdits(pdf, 0, new HashMap<>(), List.of(new TextEditItem(0, "x"))))
                .isInstanceOf(PageNotEditableException.class);
    }

    @Test
    void runCoordinatesStayInUserSpaceWhenTheCropBoxIsOffsetFromTheOrigin() throws IOException {
        byte[] pdf = TestPdfs.koreanPage(new PDRectangle(20, 30, 400, 600), 0);

        PageTextDto page = service.describePage(pdf, 0, Map.of());

        assertThat(page.originX()).isEqualTo(20f);
        assertThat(page.originY()).isEqualTo(30f);
        assertThat(page.pageWidth()).isEqualTo(400f);
        assertThat(page.pageHeight()).isEqualTo(600f);
        TextRunDto hello = run(page, "Hello World");
        assertThat(hello.x()).isCloseTo(50f, within(1f));
        assertThat(hello.y()).isCloseTo(650f, within(1f));
    }

    @Test
    void previewIsAPngOfThePageAtPreviewResolution() throws IOException {
        byte[] png = service.renderPreview(TestPdfs.koreanPage(), 0, Map.of());

        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }

    @Test
    void boldFontKeepsItsWeightWhenEditedWithCharactersAlreadyOnThePage() throws IOException {
        assertEditLooksLikeDirectlyDrawn(TestPdfs.Look.BOLD, "법률-시행령-시행규칙", "법률-시행규칙");
    }

    @Test
    void appendingACharacterTheFontAlreadyHasKeepsTheFont() throws IOException {
        assertEditLooksLikeDirectlyDrawn(TestPdfs.Look.BOLD, "법률-시행규칙", "법률-시행규칙칙");
    }

    @Test
    void textColourSurvivesAnEdit() throws IOException {
        assertEditLooksLikeDirectlyDrawn(TestPdfs.Look.BLUE, "대통령령 제35821호", "대통령령 제3582호");
    }

    @Test
    void characterSpacingAndHorizontalScaleSurviveAnEdit() throws IOException {
        assertEditLooksLikeDirectlyDrawn(TestPdfs.Look.SPACED_NARROW, "환경영향평가법 시행령", "환경영향평가 시행령");
    }

    @Test
    void type3FontIsRedrawnWithItsOwnGlyphs() throws IOException {
        // Type 3 fonts (what Hancom/HWP writes) are drawing programs with no name or font file:
        // an edit must reuse their glyphs, not swap the whole line for an installed font.
        byte[] original = TestPdfs.type3Line("ABBA");
        byte[] edited = bake(original, "ABBA", "ABAB");

        assertThat(TestPdfs.visiblyDifferentPixels(TestPdfs.render(edited),
                TestPdfs.render(TestPdfs.type3Line("ABAB")))).isLessThanOrEqualTo(TestPdfs.SUBPIXEL_NOISE);
    }

    @Test
    void aCharacterMissingFromAType3FontStillGetsDrawn() throws IOException {
        byte[] original = TestPdfs.type3Line("ABBA");
        TextRunDto target = run(service.describePage(original, 0, Map.of()), "ABBA");
        Map<Integer, RunEdit> edits = new HashMap<>();
        service.recordEdits(original, 0, edits, List.of(new TextEditItem(target.index(), "ABCA")));

        BufferedImage before = TestPdfs.render(original);
        BufferedImage after = TestPdfs.render(service.buildSinglePage(original, 0, edits));

        // "C" is not in the font, so an installed font draws it: the line changes but stays on its baseline.
        assertThat(TestPdfs.differingPixels(before, after)).isGreaterThan(0);
        PageTextDto described = service.describePage(original, 0, edits);
        assertThat(run(described, "ABBA").currentText()).isEqualTo("ABCA");
    }

    @Test
    void editedTextInAShadedTableCellLeavesNoWhitePatch() throws IOException {
        float[] shade = {0.85f, 0.85f, 0.85f};
        String before = "환경영향평가법 시행령";
        String after = "환경영향평가 시행령";

        byte[] edited = bake(TestPdfs.styledLine(before, TestPdfs.Look.PLAIN, shade), before, after);

        long differing = TestPdfs.visiblyDifferentPixels(TestPdfs.render(edited),
                TestPdfs.render(TestPdfs.styledLine(after, TestPdfs.Look.PLAIN, shade)));
        assertThat(differing).isLessThanOrEqualTo(TestPdfs.SUBPIXEL_NOISE);
    }

    @Test
    void theColourBehindATextBoxIsTheMostCommonColourThere() {
        BufferedImage page = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                page.setRGB(x, y, 0xE0E0E0);
            }
        }
        for (int y = 10; y < 14; y++) {
            for (int x = 5; x < 35; x++) {
                page.setRGB(x, y, 0x000000);
            }
        }

        java.awt.Color color = TextEditService.backgroundBehind(page, new PDRectangle(20, 20), 2, 2, 16, 16);

        assertThat(color.getRGB() & 0xFFFFFF).isEqualTo(0xE0E0E0);
    }

    @Test
    void fakeBoldDrawnWithAStrokeSurvivesAnEdit() throws IOException {
        assertEditLooksLikeDirectlyDrawn(TestPdfs.Look.FAUX_BOLD, "환경영향평가법 시행령", "환경영향평가 시행령");
    }

    @Test
    void editedTextIsNotBlackWhenTheOriginalWasBlue() throws IOException {
        byte[] edited = bake(TestPdfs.styledLine("대통령령 제35821호", TestPdfs.Look.BLUE), "대통령령 제35821호",
                "대통령령 제3582호");

        BufferedImage image = TestPdfs.render(edited);
        long blue = 0;
        long black = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int b = rgb & 0xFF;
                if (r < 100 && b > 200) {
                    blue++;
                } else if (r < 60 && b < 60) {
                    black++;
                }
            }
        }
        assertThat(blue).as("blue ink").isGreaterThan(200);
        assertThat(black).as("black ink").isLessThan(blue / 20);
    }

    /**
     * Editing must look as if the final text had been typeset that way in the first place: same
     * font design and weight, size, colour, spacing and width - compared pixel by pixel with a page
     * that draws the final text directly in the same look.
     */
    private void assertEditLooksLikeDirectlyDrawn(TestPdfs.Look look, String before, String after)
            throws IOException {
        byte[] edited = bake(TestPdfs.styledLine(before, look), before, after);

        long differing = TestPdfs.visiblyDifferentPixels(TestPdfs.render(edited),
                TestPdfs.render(TestPdfs.styledLine(after, look)));

        assertThat(differing).isLessThanOrEqualTo(TestPdfs.SUBPIXEL_NOISE);
    }

    private byte[] bake(byte[] pdf, String original, String replacement) throws IOException {
        TextRunDto target = run(service.describePage(pdf, 0, Map.of()), original);
        Map<Integer, RunEdit> edits = new HashMap<>();
        service.recordEdits(pdf, 0, edits, List.of(new TextEditItem(target.index(), replacement)));
        return service.buildSinglePage(pdf, 0, edits);
    }

    /** The edit changes some pixels, and every changed pixel lies within the edited run's own box. */
    private void assertBakedEditStaysLocal(byte[] pdf, String original, String replacement) throws IOException {
        TextRunDto target = run(service.describePage(pdf, 0, Map.of()), original);
        byte[] edited = bake(pdf, original, replacement);

        BufferedImage before = render(pdf);
        BufferedImage after = render(edited);
        int minX = Integer.MAX_VALUE;
        int maxX = -1;
        int minY = Integer.MAX_VALUE;
        int maxY = -1;
        for (int y = 0; y < before.getHeight(); y++) {
            for (int x = 0; x < before.getWidth(); x++) {
                if (before.getRGB(x, y) != after.getRGB(x, y)) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        assertThat(maxX).as("edit must change some pixels").isGreaterThanOrEqualTo(0);
        float pageHeight = PDRectangle.A4.getHeight();
        float top = pageHeight - target.boxTop();
        float bottom = pageHeight - target.boxBottom();
        assertThat(minX).isGreaterThanOrEqualTo((int) target.x() - 3);
        assertThat(minY).isGreaterThanOrEqualTo((int) top - 3);
        assertThat(maxY).isLessThanOrEqualTo((int) bottom + 3);
    }

    /**
     * Finds a run ignoring spaces and punctuation: whether a space survives text extraction depends
     * on the font the test PDF was built with (some fonts have no Unicode mapping for it, which
     * PDFBox then reports as '?').
     */
    private static TextRunDto run(PageTextDto page, String text) {
        return page.runs().stream().filter(r -> squash(r.text()).equals(squash(text))).findFirst().orElseThrow();
    }

    private static String squash(String text) {
        return text.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static BufferedImage render(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFRenderer(doc).renderImage(0, 1f);
        }
    }
}
