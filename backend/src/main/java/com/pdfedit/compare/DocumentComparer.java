package com.pdfedit.compare;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.apache.pdfbox.pdmodel.PDDocument;

import com.pdfedit.text.TextLines;

/**
 * What text was taken out of one document and put into the other, word by word, with where each change
 * is on its page.
 *
 * <p>The whole documents are compared, not page by page: text that only moved to the next page is not a
 * change. Lines are matched first and words only inside the lines that differ, which keeps a long document
 * with a few changes quick, and a changed word shows up as itself rather than as its whole line.
 */
public final class DocumentComparer {

    static final int MAX_LINE_EDITS = 3000;
    static final int MAX_WORD_EDITS = 3000;

    private DocumentComparer() {
    }

    /**
     * A change on one page: the words as they read (joined by spaces) and the area around them, one change
     * per line it touches.
     *
     * @param page 0-based page of the document the change is in
     */
    public record Change(int page, String text, TextLines.Area area) {
    }

    /** The changes: removed are in the first document, added in the second. */
    public record Comparison(List<Change> removed, List<Change> added, int removedWords, int addedWords) {
    }

    /** A word of a document and where it is: its page and its line (counted over the whole document). */
    private record Token(String key, int page, int line, TextLines.Word word) {
    }

    private record Line(String key, List<Token> words) {
    }

    public static Comparison compare(PDDocument a, PDDocument b) throws IOException {
        return compare(a, b, page -> List.of(), page -> List.of());
    }

    /**
     * Like {@link #compare(PDDocument, PDDocument)}, with the lines {@code recognizedA} and {@code recognizedB} give
     * for a page (text read off a scan) taken as part of that page's text.
     */
    public static Comparison compare(PDDocument a, PDDocument b, IntFunction<List<TextLines.Line>> recognizedA,
            IntFunction<List<TextLines.Line>> recognizedB) throws IOException {
        List<Line> linesA = lines(a, recognizedA);
        List<Line> linesB = lines(b, recognizedB);
        List<Change> removed = new ArrayList<>();
        List<Change> added = new ArrayList<>();
        int[] counts = new int[2];
        for (Diff.Hunk lines : Diff.hunks(keys(linesA), keys(linesB), MAX_LINE_EDITS)) {
            List<Token> wordsA = words(linesA.subList(lines.aFrom(), lines.aTo()));
            List<Token> wordsB = words(linesB.subList(lines.bFrom(), lines.bTo()));
            for (Diff.Hunk words : Diff.hunks(tokenKeys(wordsA), tokenKeys(wordsB), MAX_WORD_EDITS)) {
                List<Token> out = wordsA.subList(words.aFrom(), words.aTo());
                List<Token> in = wordsB.subList(words.bFrom(), words.bTo());
                counts[0] += out.size();
                counts[1] += in.size();
                removed.addAll(changes(out));
                added.addAll(changes(in));
            }
        }
        return new Comparison(removed, added, counts[0], counts[1]);
    }

    /** Every line of the document with its words; a line's key is its words, so spacing alone is no change. */
    private static List<Line> lines(PDDocument doc, IntFunction<List<TextLines.Line>> recognized) throws IOException {
        List<Line> lines = new ArrayList<>();
        for (int page = 0; page < doc.getNumberOfPages(); page++) {
            List<TextLines.Line> pageLines = new ArrayList<>(TextLines.of(doc, page));
            pageLines.addAll(recognized.apply(page));
            for (TextLines.Line line : pageLines) {
                List<Token> tokens = new ArrayList<>();
                for (TextLines.Word word : line.words()) {
                    tokens.add(new Token(Normalizer.normalize(word.text(), Normalizer.Form.NFC), page, lines.size(), word));
                }
                if (!tokens.isEmpty()) {
                    lines.add(new Line(String.join(" ", tokenKeys(tokens)), tokens));
                }
            }
        }
        return lines;
    }

    private static List<String> keys(List<Line> lines) {
        return lines.stream().map(Line::key).toList();
    }

    private static List<String> tokenKeys(List<Token> tokens) {
        return tokens.stream().map(Token::key).toList();
    }

    private static List<Token> words(List<Line> lines) {
        List<Token> words = new ArrayList<>();
        lines.forEach(line -> words.addAll(line.words()));
        return words;
    }

    /** The words as changes, one per line they are on, each with the area around its words. */
    private static List<Change> changes(List<Token> tokens) {
        List<Change> changes = new ArrayList<>();
        int from = 0;
        for (int i = 1; i <= tokens.size(); i++) {
            if (i == tokens.size() || tokens.get(i).line() != tokens.get(from).line()) {
                List<Token> line = tokens.subList(from, i);
                if (!line.isEmpty()) {
                    changes.add(new Change(line.get(0).page(), String.join(" ", tokenKeys(line)), union(line)));
                }
                from = i;
            }
        }
        return changes;
    }

    private static TextLines.Area union(List<Token> tokens) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (Token token : tokens) {
            TextLines.Area area = token.word().area();
            minX = Math.min(minX, area.x());
            minY = Math.min(minY, area.y());
            maxX = Math.max(maxX, area.x() + area.width());
            maxY = Math.max(maxY, area.y() + area.height());
        }
        return new TextLines.Area(minX, minY, maxX - minX, maxY - minY);
    }
}
