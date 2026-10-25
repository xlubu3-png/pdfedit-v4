package com.pdfedit.redact;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/**
 * Takes single glyphs out of a page's content: in the text operator that draws a glyph, its character code
 * is replaced by a TJ number that moves on by the glyph's advance, so the letters around it stay where they
 * were and the glyph itself is gone from the file. Forms that hold such glyphs are copied, not changed,
 * since their objects can be shared with other pages.
 */
final class GlyphCutter {

    private static final int MAX_DEPTH = 12;

    private GlyphCutter() {
    }

    /** stream, operator, string, code: the TJ number that stands in for the glyph. */
    private static final class Cuts {
        final Map<String, Map<Integer, Map<Integer, Map<Integer, Double>>>> byStream = new HashMap<>();

        void add(PageScan.GlyphAt glyph) {
            byStream.computeIfAbsent(glyph.stream(), k -> new HashMap<>())
                    .computeIfAbsent(glyph.op(), k -> new TreeMap<>())
                    .computeIfAbsent(glyph.string(), k -> new TreeMap<>())
                    .put(glyph.code(), glyph.adjust());
        }

        boolean reaches(String key) {
            if (byStream.containsKey(key)) {
                return true;
            }
            String prefix = key + "/";
            return byStream.keySet().stream().anyMatch(stream -> stream.startsWith(prefix));
        }
    }

    /** Thrown when an operator does not look the way the scan saw it; the page is then made a picture. */
    static final class Mismatch extends IOException {
        Mismatch(String message) {
            super(message);
        }
    }

    static void cut(PDDocument doc, PDPage page, List<PageScan.GlyphAt> glyphs, Map<String, PDFont> fonts)
            throws IOException {
        Cuts cuts = new Cuts();
        glyphs.forEach(cuts::add);
        Rewriter rewriter = new Rewriter(doc, cuts, fonts);
        Rewritten result = rewriter.stream(new PDFStreamParser(page).parse(), page.getResources(),
                PageScan.PAGE_STREAM, 0);
        if (result.tokens() != null) {
            PDStream stream = new PDStream(doc);
            try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
                new ContentStreamWriter(out).writeTokens(result.tokens());
            }
            page.setContents(stream);
        }
        if (!result.forms().isEmpty()) {
            page.setResources(withForms(page.getResources(), result.forms()));
        }
    }

    private record Rewritten(List<Object> tokens, Map<COSName, COSStream> forms) {
        static final Rewritten UNCHANGED = new Rewritten(null, Map.of());

        boolean changed() {
            return tokens != null || !forms.isEmpty();
        }
    }

    private static final class Rewriter {
        private final PDDocument doc;
        private final Cuts cuts;
        private final Map<String, PDFont> fonts;

        Rewriter(PDDocument doc, Cuts cuts, Map<String, PDFont> fonts) {
            this.doc = doc;
            this.cuts = cuts;
            this.fonts = fonts;
        }

        Rewritten stream(List<Object> tokens, PDResources resources, String key, int depth) throws IOException {
            if (!cuts.reaches(key) || depth > MAX_DEPTH) {
                return Rewritten.UNCHANGED;
            }
            Map<Integer, Map<Integer, Map<Integer, Double>>> here = cuts.byStream.getOrDefault(key, Map.of());
            List<Object> out = new ArrayList<>(tokens.size());
            List<COSBase> operands = new ArrayList<>();
            Map<COSName, COSStream> forms = new LinkedHashMap<>();
            boolean changed = false;
            int ordinal = 0;
            for (Object token : tokens) {
                if (!(token instanceof Operator operator)) {
                    if (token instanceof COSBase operand) {
                        operands.add(operand);
                    }
                    continue;
                }
                String name = operator.getName();
                if (PageScan.isShowText(name)) {
                    int op = ordinal++;
                    Map<Integer, Map<Integer, Double>> strings = here.get(op);
                    if (strings != null) {
                        PDFont font = fonts.get(PageScan.opKey(key, op));
                        if (font == null) {
                            throw new Mismatch("no font for " + key + "#" + op);
                        }
                        out.addAll(replacement(name, operands, strings, font));
                        operands.clear();
                        changed = true;
                        continue;
                    }
                } else if (OperatorName.DRAW_OBJECT.equals(name) && !operands.isEmpty()
                        && operands.get(operands.size() - 1) instanceof COSName xName && resources != null) {
                    String childKey = key + "/" + xName.getName();
                    if (cuts.reaches(childKey)) {
                        PDXObject xObject = resources.getXObject(xName);
                        if (xObject instanceof PDFormXObject form) {
                            COSStream copy = copyOfForm(form, resources, childKey, depth + 1);
                            if (copy != null) {
                                forms.put(xName, copy);
                            }
                        }
                    }
                }
                out.addAll(operands);
                out.add(operator);
                operands.clear();
            }
            return new Rewritten(changed ? out : null, forms);
        }

        private COSStream copyOfForm(PDFormXObject form, PDResources inherited, String key, int depth)
                throws IOException {
            PDResources own = form.getResources();
            PDResources used = own != null ? own : inherited;
            Rewritten inside = stream(new PDFStreamParser(form).parse(), used, key, depth);
            if (!inside.changed()) {
                return null;
            }
            COSStream source = form.getCOSObject();
            COSStream copy = doc.getDocument().createCOSStream();
            for (Map.Entry<COSName, COSBase> entry : source.entrySet()) {
                COSName name = entry.getKey();
                if (COSName.LENGTH.equals(name) || COSName.FILTER.equals(name) || COSName.DECODE_PARMS.equals(name)
                        || COSName.F.equals(name) || COSName.F_FILTER.equals(name)
                        || COSName.F_DECODE_PARMS.equals(name)) {
                    continue;
                }
                copy.setItem(name, entry.getValue());
            }
            List<Object> tokens = inside.tokens() != null ? inside.tokens() : new PDFStreamParser(form).parse();
            try (OutputStream out = copy.createOutputStream(COSName.FLATE_DECODE)) {
                new ContentStreamWriter(out).writeTokens(tokens);
            }
            if (!inside.forms().isEmpty()) {
                copy.setItem(COSName.RESOURCES, withForms(used, inside.forms()).getCOSObject());
            }
            return copy;
        }
    }

    /**
     * The tokens that draw what the operator drew, less the cut glyphs, as one TJ. A ' or " first does the
     * line move (and spacing) it implied, since TJ does not.
     */
    private static List<Object> replacement(String name, List<COSBase> operands,
            Map<Integer, Map<Integer, Double>> strings, PDFont font) throws IOException {
        List<Object> out = new ArrayList<>();
        COSArray shown = new COSArray();
        int[] stringIndex = {0};
        switch (name) {
            case OperatorName.SHOW_TEXT -> addString(lastString(operands), strings, font, shown, stringIndex);
            case OperatorName.SHOW_TEXT_ADJUSTED -> {
                if (operands.isEmpty() || !(operands.get(operands.size() - 1) instanceof COSArray array)) {
                    throw new Mismatch("TJ without an array");
                }
                for (COSBase element : array) {
                    if (element instanceof COSString string) {
                        addString(string, strings, font, shown, stringIndex);
                    } else if (element instanceof COSNumber number) {
                        shown.add(number);
                    }
                }
            }
            case OperatorName.SHOW_TEXT_LINE -> {
                out.add(Operator.getOperator(OperatorName.NEXT_LINE));
                addString(lastString(operands), strings, font, shown, stringIndex);
            }
            case OperatorName.SHOW_TEXT_LINE_AND_SPACE -> {
                if (operands.size() < 3 || !(operands.get(0) instanceof COSNumber wordSpacing)
                        || !(operands.get(1) instanceof COSNumber charSpacing)) {
                    throw new Mismatch("\" without its spacing");
                }
                out.add(wordSpacing);
                out.add(Operator.getOperator(OperatorName.SET_WORD_SPACING));
                out.add(charSpacing);
                out.add(Operator.getOperator(OperatorName.SET_CHAR_SPACING));
                out.add(Operator.getOperator(OperatorName.NEXT_LINE));
                addString(lastString(operands), strings, font, shown, stringIndex);
            }
            default -> throw new Mismatch("not a text operator: " + name);
        }
        out.add(shown);
        out.add(Operator.getOperator(OperatorName.SHOW_TEXT_ADJUSTED));
        return out;
    }

    private static COSString lastString(List<COSBase> operands) throws Mismatch {
        if (operands.isEmpty() || !(operands.get(operands.size() - 1) instanceof COSString string)) {
            throw new Mismatch("text operator without a string");
        }
        return string;
    }

    /** Adds the string to the TJ array, its cut codes turned into the numbers that stand in for them. */
    private static void addString(COSString string, Map<Integer, Map<Integer, Double>> strings, PDFont font,
            COSArray shown, int[] stringIndex) throws IOException {
        Map<Integer, Double> codes = strings.get(stringIndex[0]++);
        if (codes == null || codes.isEmpty()) {
            shown.add(string);
            return;
        }
        byte[] bytes = string.getBytes();
        List<Integer> lengths = PageScan.codeLengths(font, bytes);
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        int pos = 0;
        for (int i = 0; i < lengths.size(); i++) {
            int length = lengths.get(i);
            Double adjust = codes.get(i);
            if (adjust == null) {
                kept.write(bytes, pos, length);
            } else {
                flush(kept, string, shown);
                shown.add(new COSFloat(adjust.floatValue()));
            }
            pos += length;
        }
        if (pos < bytes.length) {
            kept.write(bytes, pos, bytes.length - pos);
        }
        flush(kept, string, shown);
    }

    private static void flush(ByteArrayOutputStream kept, COSString original, COSArray shown) {
        if (kept.size() == 0) {
            return;
        }
        COSString part = new COSString(kept.toByteArray());
        part.setForceHexForm(original.getForceHexForm());
        shown.add(part);
        kept.reset();
    }

    /** A copy of the resources in which the named forms are other streams; the originals are not touched. */
    private static PDResources withForms(PDResources resources, Map<COSName, COSStream> forms) {
        COSDictionary dictionary = resources == null ? new COSDictionary() : new COSDictionary(resources.getCOSObject());
        COSBase existing = dictionary.getDictionaryObject(COSName.XOBJECT);
        COSDictionary xObjects = existing instanceof COSDictionary found ? new COSDictionary(found) : new COSDictionary();
        forms.forEach(xObjects::setItem);
        dictionary.setItem(COSName.XOBJECT, xObjects);
        return new PDResources(dictionary);
    }
}
