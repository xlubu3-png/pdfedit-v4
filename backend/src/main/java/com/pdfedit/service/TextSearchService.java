package com.pdfedit.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;

import com.pdfedit.dto.FindMatch;
import com.pdfedit.dto.PageSpec;
import com.pdfedit.dto.ReplaceResult;
import com.pdfedit.dto.TextEditItem;
import com.pdfedit.text.FontUnavailableException;
import com.pdfedit.text.RunEdit;
import com.pdfedit.text.TextExtraction;
import com.pdfedit.text.TextRun;

/**
 * Finds and replaces text across the pages the user has. A match is inside one text line (a line
 * the PDF draws in pieces is one line here); text that wraps over two lines is not found. What is
 * searched is the text as it reads now, so earlier edits are included.
 */
@Service
public class TextSearchService {

    private static final int MAX_MATCHES = 500;

    private final PdfDocumentStore store;
    private final TextEditService textEditService;

    public TextSearchService(PdfDocumentStore store, TextEditService textEditService) {
        this.store = store;
        this.textEditService = textEditService;
    }

    public List<FindMatch> find(List<PageSpec> pages, String query, boolean matchCase) throws IOException {
        Pattern pattern = pattern(query, matchCase);
        List<FindMatch> found = new ArrayList<>();
        for (Map.Entry<String, Set<Integer>> doc : byDocument(pages).entrySet()) {
            try (PDDocument pdf = Loader.loadPDF(store.getContent(doc.getKey()))) {
                for (int pageIndex : doc.getValue()) {
                    Map<Integer, RunEdit> edits = store.pageEditsSnapshot(doc.getKey(), pageIndex).runs();
                    List<TextRun> runs = TextExtraction.extractRuns(pdf, pageIndex);
                    for (int i = 0; i < runs.size() && found.size() < MAX_MATCHES; i++) {
                        String text = currentText(runs.get(i), edits.get(i));
                        if (pattern.matcher(text).find()) {
                            found.add(new FindMatch(doc.getKey(), pageIndex, i, text));
                        }
                    }
                }
            }
        }
        return found;
    }

    public ReplaceResult replace(List<PageSpec> pages, String find, String replace, boolean matchCase)
            throws IOException {
        Pattern pattern = pattern(find, matchCase);
        String replacement = Matcher.quoteReplacement(replace == null ? "" : replace);
        int replacements = 0;
        int runsChanged = 0;
        String skipped = null;
        List<PageSpec> changedPages = new ArrayList<>();

        for (Map.Entry<String, Set<Integer>> doc : byDocument(pages).entrySet()) {
            byte[] content = store.getContent(doc.getKey());
            for (int pageIndex : doc.getValue()) {
                Map<Integer, RunEdit> existing = store.pageEditsSnapshot(doc.getKey(), pageIndex).runs();
                List<TextEditItem> items = new ArrayList<>();
                int pageReplacements = 0;
                try (PDDocument pdf = Loader.loadPDF(content)) {
                    List<TextRun> runs = TextExtraction.extractRuns(pdf, pageIndex);
                    for (int i = 0; i < runs.size(); i++) {
                        RunEdit before = existing.get(i);
                        String text = currentText(runs.get(i), before);
                        Matcher matcher = pattern.matcher(text);
                        int count = 0;
                        while (matcher.find()) {
                            count++;
                        }
                        if (count == 0) {
                            continue;
                        }
                        pageReplacements += count;
                        // Everything else the user did to the line (font, size, colour, move) stays.
                        items.add(new TextEditItem(i, pattern.matcher(text).replaceAll(replacement),
                                before == null ? null : before.fontFamily(), before == null ? null : before.fontSize(),
                                before == null ? null : before.bold(), before == null ? null : before.color(),
                                before == null ? null : before.dx(), before == null ? null : before.dy()));
                    }
                }
                if (items.isEmpty()) {
                    continue;
                }
                try {
                    textEditService.recordEdits(content, pageIndex, store.pageEdits(doc.getKey(), pageIndex), items);
                    replacements += pageReplacements;
                    runsChanged += items.size();
                    changedPages.add(new PageSpec(doc.getKey(), pageIndex, 0));
                } catch (FontUnavailableException unusable) {
                    skipped = skipped == null ? (pageIndex + 1) + "쪽을 건너뜀: " + unusable.getMessage() : skipped;
                }
            }
            store.persist(doc.getKey());
        }
        return new ReplaceResult(replacements, runsChanged, changedPages, skipped);
    }

    private static String currentText(TextRun run, RunEdit edit) {
        return edit != null && edit.text() != null ? edit.text() : run.text();
    }

    private static Pattern pattern(String text, boolean matchCase) {
        if (text == null || text.isEmpty()) {
            throw new InvalidEditException("찾을 글자를 입력하세요.");
        }
        return Pattern.compile(Pattern.quote(text), matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** The distinct pages, grouped by document, in the order the user has them. */
    private static Map<String, Set<Integer>> byDocument(List<PageSpec> pages) {
        Map<String, Set<Integer>> grouped = new LinkedHashMap<>();
        if (pages != null) {
            for (PageSpec page : pages) {
                grouped.computeIfAbsent(page.documentId(), k -> new LinkedHashSet<>()).add(page.pageIndex());
            }
        }
        return grouped;
    }
}
