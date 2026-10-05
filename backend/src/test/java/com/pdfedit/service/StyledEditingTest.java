package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.pdfedit.dto.PageSpec;
import com.pdfedit.dto.PageTextDto;
import com.pdfedit.dto.TextEditItem;
import com.pdfedit.dto.TextRunDto;
import com.pdfedit.text.AddedText;
import com.pdfedit.text.FontMatcher;
import com.pdfedit.text.FontUnavailableException;
import com.pdfedit.text.RunEdit;
import com.pdfedit.text.SystemFonts;

/** Changing a line's font, size, weight, colour or place, and adding text boxes to a page. */
class StyledEditingTest {

    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();

    private final FontMatcher fontMatcher = new FontMatcher();
    private final TextEditService service = new TextEditService(fontMatcher);

    private static TextRunDto helloLine(PageTextDto page) {
        return page.runs().stream().filter(r -> r.text().replaceAll("\\s", "").equals("HelloWorld")).findFirst()
                .orElseThrow();
    }

    /** The page with {@code item} applied to the "Hello World" line (at 50,650 pt, 12 pt), rendered at 2x. */
    private BufferedImage bake(byte[] pdf, TextEditItem item) throws IOException {
        Map<Integer, RunEdit> edits = new HashMap<>();
        service.recordEdits(pdf, 0, edits, List.of(item));
        return TestPdfs.render(service.buildSinglePage(pdf, 0, edits));
    }

    private TextEditItem edit(byte[] pdf, String family, Float size, Boolean bold, String color, Float dx, Float dy)
            throws IOException {
        TextRunDto hello = helloLine(service.describePage(pdf, 0, Map.of()));
        return new TextEditItem(hello.index(), hello.text(), family, size, bold, color, dx, dy);
    }

    /** Pixels in the box (PDF points, y up) that are clearly not paper white. */
    private static long ink(BufferedImage image, float x0, float yBottom, float x1, float yTop) {
        long count = 0;
        for (int y = (int) ((PAGE_HEIGHT - yTop) * 2); y < (int) ((PAGE_HEIGHT - yBottom) * 2); y++) {
            for (int x = (int) (x0 * 2); x < (int) (x1 * 2); x++) {
                int rgb = image.getRGB(x, y);
                int distance = (255 - ((rgb >> 16) & 0xFF)) + (255 - ((rgb >> 8) & 0xFF)) + (255 - (rgb & 0xFF));
                if (distance > 240) {
                    count++;
                }
            }
        }
        return count;
    }

    private static long pixelsOf(BufferedImage image, float x0, float yBottom, float x1, float yTop, int rgbTarget) {
        long count = 0;
        int tr = (rgbTarget >> 16) & 0xFF;
        int tg = (rgbTarget >> 8) & 0xFF;
        int tb = rgbTarget & 0xFF;
        for (int y = (int) ((PAGE_HEIGHT - yTop) * 2); y < (int) ((PAGE_HEIGHT - yBottom) * 2); y++) {
            for (int x = (int) (x0 * 2); x < (int) (x1 * 2); x++) {
                int rgb = image.getRGB(x, y);
                int d = Math.abs(((rgb >> 16) & 0xFF) - tr) + Math.abs(((rgb >> 8) & 0xFF) - tg)
                        + Math.abs((rgb & 0xFF) - tb);
                if (d < 120) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void aLargerSizeMakesTheLineBigger() throws IOException {
        byte[] pdf = TestPdfs.koreanPage();

        long original = ink(bake(pdf, edit(pdf, null, 12f, null, null, null, null)), 40, 635, 300, 690);
        long enlarged = ink(bake(pdf, edit(pdf, null, 24f, null, null, null, null)), 40, 635, 300, 690);

        assertThat(original).isGreaterThan(100);
        assertThat(enlarged).isGreaterThan(original * 2);
    }

    @Test
    void aChosenColourIsUsedAndTheOriginalIsLeftAloneOtherwise() throws IOException {
        byte[] pdf = TestPdfs.koreanPage();

        BufferedImage red = bake(pdf, edit(pdf, null, null, null, "#ff0000", null, null));
        BufferedImage same = bake(pdf, edit(pdf, null, 12f, null, null, null, null));

        assertThat(pixelsOf(red, 40, 635, 300, 690, 0xFF0000)).isGreaterThan(60);
        assertThat(pixelsOf(same, 40, 635, 300, 690, 0xFF0000)).isZero();
    }

    @Test
    void boldMakesTheStrokesHeavier() throws IOException {
        byte[] pdf = TestPdfs.koreanPage();

        long regular = ink(bake(pdf, edit(pdf, null, 12f, false, null, null, null)), 40, 635, 300, 690);
        long bold = ink(bake(pdf, edit(pdf, null, 12f, true, null, null, null)), 40, 635, 300, 690);

        assertThat(bold).isGreaterThan((long) (regular * 1.05));
    }

    @Test
    void anotherFontChangesTheLook() throws IOException {
        List<SystemFonts.Family> usable = fontMatcher.families().stream().filter(f -> {
            try {
                fontMatcher.checkCovers(f.name(), false, "Hello World");
                return f.korean();
            } catch (FontUnavailableException unusable) {
                return false;
            }
        }).toList();
        Assumptions.assumeTrue(usable.size() >= 2, "needs two installed Korean fonts");
        byte[] pdf = TestPdfs.koreanPage();

        BufferedImage first = bake(pdf, edit(pdf, usable.get(0).name(), 12f, false, null, null, null));
        BufferedImage last = bake(pdf, edit(pdf, usable.get(usable.size() - 1).name(), 12f, false, null, null, null));

        assertThat(TestPdfs.visiblyDifferentPixels(first, last)).isGreaterThan(100);
    }

    @Test
    void aFontWithoutTheCharactersIsRefusedWhenSavedInsteadOfDroppingTheText() throws IOException {
        Set<String> symbolFonts = Set.of("Webdings", "Wingdings", "Marlett", "Symbol");
        String symbol = fontMatcher.families().stream().map(SystemFonts.Family::name).filter(symbolFonts::contains)
                .findFirst().orElse(null);
        Assumptions.assumeTrue(symbol != null, "needs a symbol-only font");
        byte[] pdf = TestPdfs.koreanPage();
        TextRunDto hello = helloLine(service.describePage(pdf, 0, Map.of()));

        assertThatThrownBy(() -> service.recordEdits(pdf, 0, new HashMap<>(),
                List.of(new TextEditItem(hello.index(), "한글", symbol, null, null, null, null, null))))
                .isInstanceOf(FontUnavailableException.class);
    }

    @Test
    void aMovedLineLeavesItsOldPlaceCoveredAndAppearsWhereItWasMoved() throws IOException {
        byte[] pdf = TestPdfs.koreanPage();

        BufferedImage moved = bake(pdf, edit(pdf, null, null, null, null, 120f, -60f));

        assertThat(ink(moved, 40, 640, 150, 668)).isZero();
        assertThat(ink(moved, 165, 575, 300, 605)).isGreaterThan(60);
    }

    @Test
    void movingAndMovingBackLeavesNoEdit() throws IOException {
        byte[] pdf = TestPdfs.koreanPage();
        TextRunDto hello = helloLine(service.describePage(pdf, 0, Map.of()));
        Map<Integer, RunEdit> edits = new HashMap<>();

        service.recordEdits(pdf, 0, edits, List.of(new TextEditItem(hello.index(), hello.text(), null, null, null, null, 30f, 0f)));
        assertThat(edits.get(hello.index()).moved()).isTrue();
        PageTextDto described = service.describePage(pdf, 0, edits);
        assertThat(helloLine(described).dx()).isEqualTo(30f);
        assertThat(helloLine(described).edited()).isTrue();

        service.recordEdits(pdf, 0, edits, List.of(new TextEditItem(hello.index(), hello.text())));
        assertThat(edits).isEmpty();
    }

    @Test
    void theDescriptionNamesTheOriginalFontAndColourAndReportsTheUsersChoices() throws IOException {
        byte[] pdf = TestPdfs.koreanPage();
        TextRunDto hello = helloLine(service.describePage(pdf, 0, Map.of()));
        assertThat(hello.sourceFont()).isNotBlank().doesNotContain("+");
        assertThat(hello.color()).matches("#[0-9a-f]{6}");

        String family = fontMatcher.families().get(0).name();
        Map<Integer, RunEdit> edits = new HashMap<>();
        service.recordEdits(pdf, 0, edits, List.of(
                new TextEditItem(hello.index(), "Hello World", family, 20f, true, "#336699", null, null)));
        TextRunDto after = helloLine(service.describePage(pdf, 0, edits));

        assertThat(after.editFontFamily()).isEqualTo(family);
        assertThat(after.editFontSize()).isEqualTo(20f);
        assertThat(after.editBold()).isTrue();
        assertThat(after.editColor()).isEqualTo("#336699");
    }

    @Test
    void nonsenseSizesAndColoursAreRejected() throws IOException {
        byte[] pdf = TestPdfs.koreanPage();
        TextRunDto hello = helloLine(service.describePage(pdf, 0, Map.of()));

        assertThatThrownBy(() -> service.recordEdits(pdf, 0, new HashMap<>(),
                List.of(new TextEditItem(hello.index(), "x", null, 0f, null, null, null, null))))
                .isInstanceOf(InvalidEditException.class);
        assertThatThrownBy(() -> service.recordEdits(pdf, 0, new HashMap<>(),
                List.of(new TextEditItem(hello.index(), "x", null, null, null, "red", null, null))))
                .isInstanceOf(InvalidEditException.class);
        assertThatThrownBy(() -> service.checkAdded(pdf, 0,
                List.of(new AddedText("a", "x", 10, 10, null, 0f, false, null))))
                .isInstanceOf(InvalidEditException.class);
    }

    @Test
    void addedTextIsDrawnLineByLineInItsColourWhereItWasPlaced() throws IOException {
        byte[] pdf = TestPdfs.koreanPage();
        List<AddedText> boxes = service.checkAdded(pdf, 0,
                List.of(new AddedText("a", "첫 줄\n두 번째 줄", 100, 400, null, 20, false, "#0000ff")));

        BufferedImage page = TestPdfs.render(service.buildSinglePage(pdf, 0, new PageEdits(Map.of(), boxes)));

        assertThat(pixelsOf(page, 95, 396, 300, 420, 0x0000FF)).isGreaterThan(30);
        assertThat(pixelsOf(page, 95, 370, 300, 393, 0x0000FF)).isGreaterThan(30);
        assertThat(pixelsOf(page, 95, 440, 300, 470, 0x0000FF)).isZero();
    }

    @Test
    void addedTextAppearsOnABlankPageAndInTheExport() throws IOException {
        PdfDocumentStore store = new PdfDocumentStore();
        PdfExportService export = new PdfExportService(store, service);
        byte[] blank = TestPdfs.blankPage();
        String id = store.store("blank.pdf", blank, 1);
        store.setAddedTexts(id, 0, service.checkAdded(blank, 0,
                List.of(new AddedText("a", "Hello 안녕", 72, 700, null, 24, true, "#cc0000"))));

        BufferedImage exported = TestPdfs.render(export.export(List.of(new PageSpec(id, 0, 0))));

        assertThat(pixelsOf(exported, 70, 690, 300, 730, 0xCC0000)).isGreaterThan(80);
    }
}
