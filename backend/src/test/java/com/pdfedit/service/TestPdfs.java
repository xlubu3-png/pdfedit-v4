package com.pdfedit.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import com.pdfedit.text.SystemFonts;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;

/** Builds small in-memory PDFs for tests. */
public final class TestPdfs {

    private TestPdfs() {
    }

    /**
     * One A4 page with a Korean line at (50, 700) and a Latin line at (50, 650), both 12pt in a
     * fully embedded Nanum Gothic. A full embedding keeps the font's cmap table, so the editor can
     * re-embed the page's own font for redrawn text.
     */
    public static byte[] koreanPage() throws IOException {
        return koreanPage("Hello World", null, 0, false);
    }

    /** Same page with a different Latin line, e.g. as the reference for what an edit should look like. */
    static byte[] koreanPage(String latinLine) throws IOException {
        return koreanPage(latinLine, null, 0, false);
    }

    static byte[] koreanPage(PDRectangle cropBox, int rotation) throws IOException {
        return koreanPage("Hello World", cropBox, rotation, false);
    }

    /**
     * The same page with the font embedded as a PDFBox subset. Like most real-world subsets it has
     * no cmap table, so the page's own font cannot be reused and edits need a substitute font.
     */
    static byte[] subsetKoreanPage() throws IOException {
        return koreanPage("Hello World", null, 0, true);
    }

    /**
     * Pixels (out of ~2 million at 2x) that may differ between two renderings of the same text:
     * glyphs placed one by one land a float rounding away from glyphs placed by a single Tj.
     */
    public static final int SUBPIXEL_NOISE = 20;

    /**
     * A shaded table cell (30..530 x 630..660) with a heavy black rule along its top (4 pt thick, its
     * lower edge at {@code ruleBottom}) and one 10 pt line at (50, 648): like the heading cell of a
     * table with heavy outer rules, where the text sits right under the line. The text may be empty.
     */
    static byte[] shadedCellUnderThickRule(String text, float ruleBottom) throws IOException {
        SystemFonts.Face face = koreanFont();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font;
            try (InputStream is = face.openStandalone()) {
                font = PDType0Font.load(doc, is, true);
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0.9f, 0.9f, 0.9f);
                cs.addRect(30, 630, 500, 30);
                cs.fill();
                cs.setNonStrokingColor(0f, 0f, 0f);
                cs.addRect(30, ruleBottom, 500, 4);
                cs.fill();
                if (!text.isEmpty()) {
                    cs.beginText();
                    cs.setFont(font, 10);
                    cs.newLineAtOffset(50, 648);
                    cs.showText(text);
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** One empty A4 page. */
    static byte[] blankPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** The page rendered at 2x, for pixel comparisons. */
    static BufferedImage render(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFRenderer(doc).renderImage(0, 2f);
        }
    }

    /**
     * Pixels that differ visibly (some colour channel by more than 64 of 255) between two equally
     * sized images. Unlike {@link #differingPixels} this ignores the faint anti-aliasing shifts a
     * glyph gets when it lands a hundredth of a point away.
     */
    public static long visiblyDifferentPixels(BufferedImage a, BufferedImage b) {
        long count = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                int channel = Math.max(Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)),
                        Math.max(Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF)), Math.abs((p & 0xFF) - (q & 0xFF))));
                if (channel > 64) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Number of pixels that differ between two equally sized images. */
    static long differingPixels(BufferedImage a, BufferedImage b) {
        long count = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static byte[] koreanPage(String latinLine, PDRectangle cropBox, int rotation, boolean embedSubset)
            throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            if (cropBox != null) {
                page.setCropBox(cropBox);
            }
            page.setRotation(rotation);
            doc.addPage(page);
            PDType0Font font;
            try (InputStream is = koreanFont().openStandalone()) {
                font = PDType0Font.load(doc, is, embedSubset);
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                line(cs, font, 50, 700, "안녕하세요 세계");
                line(cs, font, 50, 650, latinLine);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** A standalone Korean TrueType font installed on this machine; the calling test is skipped if none is. */
    private static SystemFonts.Face koreanFont() {
        return koreanFont(false);
    }

    private static SystemFonts.Face koreanFont(boolean bold) {
        for (String name : List.of("malgungothic", "nanumgothic", "dotum")) {
            Optional<SystemFonts.Face> face = SystemFonts.system().find(bold, name).filter(f -> !f.inCollection());
            if (face.isPresent()) {
                return face.get();
            }
        }
        return Assumptions.abort("no standalone Korean TrueType font installed on this machine");
    }

    /**
     * One 20pt line at (50, 650) in a hand-made Type 3 font - like Hancom/HWP output, its glyphs are
     * drawing programs and the font has no name or font file. It has two glyphs: "A", a tall block,
     * and "B", a low bar.
     */
    public static byte[] type3Line(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            COSDictionary charProcs = new COSDictionary();
            charProcs.setItem(COSName.getPDFName("A"), glyphProgram(doc, "800 0 50 0 750 700 d1\n50 0 700 700 re f\n"));
            charProcs.setItem(COSName.getPDFName("B"), glyphProgram(doc, "800 0 50 0 750 300 d1\n50 0 700 300 re f\n"));

            COSArray differences = new COSArray();
            differences.add(COSInteger.get(65));
            differences.add(COSName.getPDFName("A"));
            differences.add(COSName.getPDFName("B"));
            COSDictionary encoding = new COSDictionary();
            encoding.setItem(COSName.TYPE, COSName.ENCODING);
            encoding.setItem(COSName.DIFFERENCES, differences);

            COSArray matrix = new COSArray();
            for (float v : new float[] {0.001f, 0f, 0f, 0.001f, 0f, 0f}) {
                matrix.add(new COSFloat(v));
            }
            COSArray widths = new COSArray();
            widths.add(COSInteger.get(800));
            widths.add(COSInteger.get(800));

            COSDictionary fontDict = new COSDictionary();
            fontDict.setItem(COSName.TYPE, COSName.FONT);
            fontDict.setItem(COSName.SUBTYPE, COSName.getPDFName("Type3"));
            fontDict.setItem(COSName.FONT_BBOX, new PDRectangle(0, 0, 1000, 1000).getCOSObject());
            fontDict.setItem(COSName.FONT_MATRIX, matrix);
            fontDict.setItem(COSName.CHAR_PROCS, charProcs);
            fontDict.setItem(COSName.ENCODING, encoding);
            fontDict.setInt(COSName.FIRST_CHAR, 65);
            fontDict.setInt(COSName.LAST_CHAR, 66);
            fontDict.setItem(COSName.WIDTHS, widths);
            PDType3Font font = new PDType3Font(fontDict);

            StringBuilder hex = new StringBuilder("<");
            for (char c : text.toCharArray()) {
                hex.append(String.format("%02X", (int) c));
            }
            hex.append("> Tj\n");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 20);
                cs.newLineAtOffset(50, 650);
                cs.appendRawCommands(hex.toString());
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static COSStream glyphProgram(PDDocument doc, String operators) throws IOException {
        COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream os = stream.createOutputStream()) {
            os.write(operators.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        return stream;
    }

    /** How a test line is drawn: weight, colour, character spacing, horizontal scaling and faux bold. */
    public record Look(boolean bold, float[] rgb, float charSpacing, float horizontalScalePercent, boolean fauxBold) {
        public static final Look PLAIN = new Look(false, new float[] {0f, 0f, 0f}, 0f, 100f, false);
        public static final Look BOLD = new Look(true, new float[] {0f, 0f, 0f}, 0f, 100f, false);
        public static final Look BLUE = new Look(false, new float[] {0f, 0f, 1f}, 0f, 100f, false);
        public static final Look SPACED_NARROW = new Look(false, new float[] {0f, 0f, 0f}, 1.5f, 80f, false);
        public static final Look FAUX_BOLD = new Look(false, new float[] {0f, 0f, 0f}, 0f, 100f, true);
    }

    /**
     * One 14pt line at (50, 650) with its font embedded as a subset - like most real PDFs, so the
     * font has no character map and the editor cannot look characters up in it.
     */
    public static byte[] styledLine(String text, Look look) throws IOException {
        return styledLine(text, look, null);
    }

    /** The same line on a coloured rectangle, like text in a shaded table cell. */
    public static byte[] styledLine(String text, Look look, float[] background) throws IOException {
        SystemFonts.Face face = koreanFont(look.bold());
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font;
            try (InputStream is = face.openStandalone()) {
                font = PDType0Font.load(doc, is, true);
            }
            float[] c = look.rgb();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                if (background != null) {
                    cs.setNonStrokingColor(background[0], background[1], background[2]);
                    cs.addRect(30, 630, 500, 50);
                    cs.fill();
                }
                cs.setNonStrokingColor(c[0], c[1], c[2]);
                if (look.fauxBold()) {
                    cs.setRenderingMode(RenderingMode.FILL_STROKE);
                    cs.setLineWidth(0.6f);
                    cs.setStrokingColor(c[0], c[1], c[2]);
                }
                cs.beginText();
                cs.setFont(font, 14);
                cs.setCharacterSpacing(look.charSpacing());
                cs.setHorizontalScaling(look.horizontalScalePercent());
                cs.newLineAtOffset(50, 650);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void line(PDPageContentStream cs, PDType0Font font, float x, float y, String text)
            throws IOException {
        cs.beginText();
        cs.setFont(font, 12);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
