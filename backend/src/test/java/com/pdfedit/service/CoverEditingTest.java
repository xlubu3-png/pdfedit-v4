package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import com.pdfedit.dto.TextEditItem;
import com.pdfedit.text.FontMatcher;
import com.pdfedit.text.RunEdit;

/**
 * Editing text in a shaded table cell that sits right under a heavy rule: the rule must stay as
 * thick as it was and the cell must keep its shade (no white patch) - the cover-up hides the old
 * letters only.
 */
class CoverEditingTest {

    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    /** The rule spans 658..662 pt; the line's cover box (up to ~658.5 pt) used to reach into it. */
    private static final float RULE_BOTTOM = 658f;

    private final TextEditService service = new TextEditService(new FontMatcher());

    private BufferedImage edited(byte[] pdf, String replacement) throws IOException {
        int index = service.describePage(pdf, 0, Map.of()).runs().get(0).index();
        Map<Integer, RunEdit> edits = new HashMap<>();
        service.recordEdits(pdf, 0, edits, List.of(new TextEditItem(index, replacement)));
        return TestPdfs.render(service.buildSinglePage(pdf, 0, edits));
    }

    /** Pixels in the box (PDF points, y up) that differ clearly between two 2x renderings. */
    private static long differing(BufferedImage a, BufferedImage b, float x0, float yBottom, float x1, float yTop) {
        long count = 0;
        for (int y = (int) ((PAGE_HEIGHT - yTop) * 2); y < (int) ((PAGE_HEIGHT - yBottom) * 2); y++) {
            for (int x = (int) (x0 * 2); x < (int) (x1 * 2); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                int d = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF));
                if (d > 90) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void deletingTheTextLeavesTheRuleAndTheShadeAsIfItHadNeverBeenThere() throws IOException {
        byte[] pdf = TestPdfs.shadedCellUnderThickRule("시행령", RULE_BOTTOM);
        BufferedImage untouchedBlank = TestPdfs.render(TestPdfs.shadedCellUnderThickRule("", RULE_BOTTOM));

        BufferedImage after = edited(pdf, "");

        assertThat(differing(after, untouchedBlank, 40, 632, 200, 666)).isLessThan(20);
    }

    @Test
    void theRuleKeepsItsThicknessWhenTheTextUnderItIsChanged() throws IOException {
        byte[] pdf = TestPdfs.shadedCellUnderThickRule("시행령", RULE_BOTTOM);
        BufferedImage before = TestPdfs.render(pdf);

        BufferedImage after = edited(pdf, "시행령영곰");

        assertThat(differing(after, before, 40, RULE_BOTTOM, 240, RULE_BOTTOM + 4)).isZero();
    }

    @Test
    void theCoverIsTheShadeOfTheCellNotWhite() throws IOException {
        byte[] pdf = TestPdfs.shadedCellUnderThickRule("시행령", RULE_BOTTOM);

        BufferedImage after = edited(pdf, "");

        // inside the old letters' area and just above their ink, still inside the cell
        for (float[] spot : new float[][] {{58, 651}, {64, 649}, {70, 653}}) {
            int rgb = after.getRGB((int) (spot[0] * 2), (int) ((PAGE_HEIGHT - spot[1]) * 2)) & 0xFFFFFF;
            assertThat((rgb >> 16) & 0xFF).as("red at %s", spot[0]).isBetween(225, 235);
            assertThat(rgb & 0xFF).as("blue at %s", spot[0]).isBetween(225, 235);
        }
    }

    @Test
    void ordinaryTextOnAPlainPageIsStillCoveredCompletely() throws IOException {
        byte[] pdf = TestPdfs.koreanPage();
        BufferedImage blank = TestPdfs.render(TestPdfs.blankPage());
        int hello = service.describePage(pdf, 0, Map.of()).runs().stream()
                .filter(r -> r.text().startsWith("Hello")).findFirst().orElseThrow().index();
        Map<Integer, RunEdit> edits = new HashMap<>();
        service.recordEdits(pdf, 0, edits, List.of(new TextEditItem(hello, "")));

        BufferedImage after = TestPdfs.render(service.buildSinglePage(pdf, 0, edits));

        // the "Hello World" line (at 50,650, 12 pt) is gone; nothing of it shows against the blank page
        assertThat(differing(after, blank, 40, 640, 200, 668)).isLessThan(10);
    }
}
