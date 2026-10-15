package com.pdfedit.service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import com.pdfedit.text.PageExtraction;
import com.pdfedit.text.PageExtraction.ShowOp;
import com.pdfedit.text.ShowOpRef;
import com.pdfedit.text.TextExtraction;
import com.pdfedit.text.TextRun;

/**
 * Takes an edited line's original text out of the page instead of painting over it: the operator that
 * drew it is replaced by a plain move of the same length, so the text after it stays where it was and the
 * letters are gone from the file. Nothing is painted over, so a rule or a cell shading next to the text is
 * left alone as well.
 *
 * <p>It only does so when it can prove it harmless: the page's text is read again without the cut
 * operators, and nothing but the edited lines may have changed. Whatever cannot be proven (an operator
 * shared with a line that is not edited, a form drawn twice, vertical writing, a Type 3 glyph's own text)
 * is left to the covering-up the editor has always done.
 */
final class TextRemoval {

    private static final int MAX_DEPTH = 12;
    /** More edited lines than this are cut all together or not at all, to keep the page's checks few. */
    private static final int MAX_ONE_BY_ONE = 8;

    private TextRemoval() {
    }

    /** What can be cut: the edited lines (by run index) and, for each operator, the TJ number that replaces it. */
    record Plan(Set<Integer> runs, Map<ShowOpRef, Double> ops) {
        static final Plan NONE = new Plan(Set.of(), Map.of());

        boolean isEmpty() {
            return runs.isEmpty();
        }
    }

    /**
     * Which of the {@code edited} runs of a page can be cut out of its content, checked by reading the
     * page's text again without them.
     */
    static Plan plan(PDDocument srcDoc, int pageIndex, PageExtraction ex, Collection<Integer> edited) {
        Set<Integer> editedSet = new TreeSet<>();
        for (int index : edited) {
            if (index >= 0 && index < ex.runs().size()) {
                editedSet.add(index);
            }
        }
        if (editedSet.isEmpty()) {
            return Plan.NONE;
        }
        Map<ShowOpRef, Set<Integer>> runsOfOp = new HashMap<>();
        for (int i = 0; i < ex.runOps().size(); i++) {
            for (ShowOpRef op : ex.runOps().get(i)) {
                runsOfOp.computeIfAbsent(op, k -> new LinkedHashSet<>()).add(i);
            }
        }
        Map<Integer, Map<ShowOpRef, Double>> candidates = new LinkedHashMap<>();
        for (int index : editedSet) {
            Map<ShowOpRef, Double> cuts = cutsFor(ex, index, runsOfOp, editedSet);
            if (cuts != null) {
                candidates.put(index, cuts);
            }
        }
        if (candidates.isEmpty()) {
            return Plan.NONE;
        }
        if (verified(srcDoc, pageIndex, ex, candidates)) {
            return planOf(candidates);
        }
        if (candidates.size() > MAX_ONE_BY_ONE) {
            return Plan.NONE;
        }
        // Something in the whole set did not check out: keep the lines that do on their own.
        Map<Integer, Map<ShowOpRef, Double>> accepted = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<ShowOpRef, Double>> candidate : candidates.entrySet()) {
            Map<Integer, Map<ShowOpRef, Double>> trial = new LinkedHashMap<>(accepted);
            trial.put(candidate.getKey(), candidate.getValue());
            if (verified(srcDoc, pageIndex, ex, trial)) {
                accepted = trial;
            }
        }
        return accepted.isEmpty() ? Plan.NONE : planOf(accepted);
    }

    private static Plan planOf(Map<Integer, Map<ShowOpRef, Double>> cuts) {
        Map<ShowOpRef, Double> ops = new LinkedHashMap<>();
        cuts.values().forEach(ops::putAll);
        return new Plan(new LinkedHashSet<>(cuts.keySet()), ops);
    }

    /** The operators to cut for one run, or null when the run cannot be cut out. */
    private static Map<ShowOpRef, Double> cutsFor(PageExtraction ex, int index, Map<ShowOpRef, Set<Integer>> runsOfOp,
            Set<Integer> edited) {
        Set<ShowOpRef> ops = ex.runOps().get(index);
        if (ops.isEmpty()) {
            return null;
        }
        Map<ShowOpRef, Double> cuts = new LinkedHashMap<>();
        for (ShowOpRef ref : ops) {
            ShowOp op = ex.ops().get(ref);
            if (op == null || !op.cuttable() || repeated(ref, ex.repeated())) {
                return null;
            }
            for (int other : runsOfOp.getOrDefault(ref, Set.of())) {
                if (!edited.contains(other)) {
                    return null; // the same operator also draws a line that stays
                }
            }
            cuts.put(ref, op.adjust());
        }
        // Operators that put the same letters on top of each other (a faux bold) show up in no run.
        TextRun run = ex.runs().get(index);
        for (ShowOp op : ex.ops().values()) {
            if (cuts.containsKey(op.ref()) || runsOfOp.containsKey(op.ref()) || !op.cuttable()
                    || repeated(op.ref(), ex.repeated())) {
                continue;
            }
            boolean inside = op.minX() >= run.x() - 1f && op.maxX() <= run.x() + run.width() + 1f
                    && Math.abs(op.baseline() - run.y()) <= Math.max(run.fontSize() * 0.4f, 1f);
            if (inside) {
                cuts.put(op.ref(), op.adjust());
            }
        }
        return cuts;
    }

    private static boolean repeated(ShowOpRef ref, Set<String> repeatedStreams) {
        for (String stream : repeatedStreams) {
            if (ref.stream().equals(stream) || ref.stream().startsWith(stream + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Reads the page's text again without the cuts and compares it with the page as it was. */
    private static boolean verified(PDDocument srcDoc, int pageIndex, PageExtraction before,
            Map<Integer, Map<ShowOpRef, Double>> cuts) {
        Map<ShowOpRef, Double> all = new LinkedHashMap<>();
        cuts.values().forEach(all::putAll);
        try (PDDocument temp = new PDDocument()) {
            PDPage source = srcDoc.getPage(pageIndex);
            PDPage copy = temp.importPage(source);
            apply(source, temp, copy, all);
            PageExtraction after = TextExtraction.extractWithOps(temp, 0);

            Map<String, Integer> expected = new HashMap<>();
            for (int i = 0; i < before.runs().size(); i++) {
                if (!cuts.containsKey(i)) {
                    expected.merge(key(before.runs().get(i)), 1, Integer::sum);
                }
            }
            for (TextRun run : after.runs()) {
                if (expected.merge(key(run), -1, Integer::sum) < 0) {
                    return false; // a line that was not there before
                }
            }
            return expected.values().stream().allMatch(count -> count == 0); // none of the others went missing
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String key(TextRun run) {
        return run.text().strip() + "|" + Math.round(run.x() * 2) + "|" + Math.round(run.y() * 2);
    }

    /**
     * Writes into {@code outPage} (a page of {@code outDoc} imported from {@code source}) the content of
     * {@code source} with the given operators replaced by plain moves. Forms the cuts reach are copied
     * rather than changed, because their objects are shared with the source document.
     */
    static void apply(PDPage source, PDDocument outDoc, PDPage outPage, Map<ShowOpRef, Double> cuts)
            throws IOException {
        if (cuts.isEmpty()) {
            return;
        }
        Map<String, Map<Integer, Double>> byStream = new HashMap<>();
        for (Map.Entry<ShowOpRef, Double> cut : cuts.entrySet()) {
            byStream.computeIfAbsent(cut.getKey().stream(), k -> new HashMap<>())
                    .put(cut.getKey().ordinal(), cut.getValue());
        }
        Rewriter rewriter = new Rewriter(outDoc, byStream);
        Rewritten page = rewriter.stream(new PDFStreamParser(source).parse(), source.getResources(),
                TextExtraction.PAGE_STREAM, 0);
        if (page.tokens() != null) {
            PDStream stream = new PDStream(outDoc);
            try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
                new ContentStreamWriter(out).writeTokens(page.tokens());
            }
            outPage.setContents(stream);
        }
        if (!page.forms().isEmpty()) {
            outPage.setResources(withForms(source.getResources(), page.forms()));
        }
    }

    /** A content stream after cutting: its tokens (null when unchanged) and the forms it now draws instead. */
    private record Rewritten(List<Object> tokens, Map<COSName, COSStream> forms) {
        static final Rewritten UNCHANGED = new Rewritten(null, Map.of());

        boolean changed() {
            return tokens != null || !forms.isEmpty();
        }
    }

    private static final class Rewriter {
        private final PDDocument outDoc;
        private final Map<String, Map<Integer, Double>> byStream;

        Rewriter(PDDocument outDoc, Map<String, Map<Integer, Double>> byStream) {
            this.outDoc = outDoc;
            this.byStream = byStream;
        }

        private boolean reaches(String key) {
            if (byStream.containsKey(key)) {
                return true;
            }
            String prefix = key + "/";
            return byStream.keySet().stream().anyMatch(stream -> stream.startsWith(prefix));
        }

        Rewritten stream(List<Object> tokens, PDResources resources, String key, int depth) throws IOException {
            if (!reaches(key) || depth > MAX_DEPTH) {
                return Rewritten.UNCHANGED;
            }
            Map<Integer, Double> cuts = byStream.getOrDefault(key, Map.of());
            List<Object> out = new ArrayList<>(tokens.size());
            List<COSBase> operands = new ArrayList<>();
            Map<COSName, COSStream> forms = new LinkedHashMap<>();
            boolean cut = false;
            int ordinal = 0;
            for (Object token : tokens) {
                if (!(token instanceof Operator operator)) {
                    if (token instanceof COSBase operand) {
                        operands.add(operand);
                    }
                    continue;
                }
                String name = operator.getName();
                if (isShowText(name)) {
                    Double adjust = cuts.get(ordinal++);
                    if (adjust != null) {
                        COSArray move = new COSArray();
                        move.add(new COSFloat(adjust.floatValue()));
                        out.add(move);
                        out.add(Operator.getOperator(OperatorName.SHOW_TEXT_ADJUSTED));
                        cut = true;
                        operands.clear();
                        continue;
                    }
                } else if (OperatorName.DRAW_OBJECT.equals(name) && !operands.isEmpty()
                        && operands.get(operands.size() - 1) instanceof COSName xName && resources != null) {
                    String childKey = key + "/" + xName.getName();
                    if (reaches(childKey)) {
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
            return new Rewritten(cut ? out : null, forms);
        }

        /** The form with its cuts made, as a new stream; null if nothing in it changed. */
        private COSStream copyOfForm(PDFormXObject form, PDResources inherited, String key, int depth)
                throws IOException {
            PDResources own = form.getResources();
            PDResources used = own != null ? own : inherited;
            Rewritten inside = stream(new PDFStreamParser(form).parse(), used, key, depth);
            if (!inside.changed()) {
                return null;
            }
            COSStream source = form.getCOSObject();
            COSStream copy = outDoc.getDocument().createCOSStream();
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

    /** A copy of the resources in which the named forms are other streams; the originals are not touched. */
    private static PDResources withForms(PDResources resources, Map<COSName, COSStream> forms) {
        COSDictionary dictionary = resources == null ? new COSDictionary() : new COSDictionary(resources.getCOSObject());
        COSBase existing = dictionary.getDictionaryObject(COSName.XOBJECT);
        COSDictionary xObjects = existing instanceof COSDictionary found ? new COSDictionary(found) : new COSDictionary();
        forms.forEach(xObjects::setItem);
        dictionary.setItem(COSName.XOBJECT, xObjects);
        return new PDResources(dictionary);
    }

    private static boolean isShowText(String operator) {
        return OperatorName.SHOW_TEXT.equals(operator) || OperatorName.SHOW_TEXT_ADJUSTED.equals(operator)
                || OperatorName.SHOW_TEXT_LINE.equals(operator) || OperatorName.SHOW_TEXT_LINE_AND_SPACE.equals(operator);
    }
}
