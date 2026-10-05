package com.pdfedit.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Draws a page exactly as it is but without any text: what the page looks like behind its letters
 * - cell fills, table rules, shading, pictures. Comparing that with the normal rendering shows
 * precisely where the letters' ink is, and the text-free picture is what an edited line is
 * covered with, so a rule that runs close to the text is not painted over.
 *
 * <p>Works on a copy: the text-showing operators are cut out of the page's content stream and of
 * the form XObjects it draws, in a document loaded from {@code pdf} just for this.
 */
final class TextFreeRenderer {

    private TextFreeRenderer() {
    }

    static BufferedImage render(byte[] pdf, int pageIndex, float scale) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage page = doc.getPage(pageIndex);
            Set<Object> done = Collections.newSetFromMap(new IdentityHashMap<>());
            writeWithoutText(doc, page, tokensWithoutText(new PDFStreamParser(page)));
            stripForms(doc, page.getResources(), done);
            return new PDFRenderer(doc).renderImage(pageIndex, scale);
        }
    }

    /** Recurses into the form XObjects a content stream draws: text is often inside one. */
    private static void stripForms(PDDocument doc, PDResources resources, Set<Object> done) throws IOException {
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xObject = resources.getXObject(name);
            if (xObject instanceof PDFormXObject form && done.add(form.getCOSObject())) {
                List<Object> tokens = tokensWithoutText(new PDFStreamParser(form));
                try (OutputStream out = form.getContentStream().createOutputStream(COSName.FLATE_DECODE)) {
                    new ContentStreamWriter(out).writeTokens(tokens);
                }
                stripForms(doc, form.getResources(), done);
            }
        }
    }

    private static void writeWithoutText(PDDocument doc, PDPage page, List<Object> tokens) throws IOException {
        PDStream stream = new PDStream(doc);
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            new ContentStreamWriter(out).writeTokens(tokens);
        }
        page.setContents(stream);
    }

    /** The tokens of a content stream with every text-showing operator, and its operands, removed. */
    private static List<Object> tokensWithoutText(PDFStreamParser parser) throws IOException {
        List<Object> kept = new ArrayList<>();
        List<COSBase> operands = new ArrayList<>();
        for (Object token : parser.parse()) {
            if (token instanceof Operator operator) {
                if (!showsText(operator.getName())) {
                    kept.addAll(operands);
                    kept.add(operator);
                }
                operands.clear();
            } else if (token instanceof COSBase operand) {
                operands.add(operand);
            }
        }
        return kept;
    }

    private static boolean showsText(String operator) {
        return OperatorName.SHOW_TEXT.equals(operator) || OperatorName.SHOW_TEXT_ADJUSTED.equals(operator)
                || OperatorName.SHOW_TEXT_LINE.equals(operator) || OperatorName.SHOW_TEXT_LINE_AND_SPACE.equals(operator);
    }
}
