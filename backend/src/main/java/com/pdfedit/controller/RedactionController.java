package com.pdfedit.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pdfedit.dto.PageSpec;
import com.pdfedit.dto.PersonalInfoMatch;
import com.pdfedit.dto.PersonalInfoRequest;
import com.pdfedit.redact.PersonalInfoFinder;
import com.pdfedit.redact.Redaction;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PdfDocumentStore;

/** The areas of a page that are redacted on export, and the search for personal information to redact. */
@RestController
@RequestMapping("/api/v1/pdf")
public class RedactionController {

    static final int MAX_AREAS = 500;
    static final int MAX_MATCHES = 5000;
    private static final float MAX_COORDINATE = 20_000f;

    private final PdfDocumentStore store;

    public RedactionController(PdfDocumentStore store) {
        this.store = store;
    }

    @GetMapping("/documents/{documentId}/pages/{pageIndex}/redactions")
    public List<Redaction> redactions(@PathVariable String documentId, @PathVariable int pageIndex) {
        checkPage(documentId, pageIndex);
        return store.redactions(documentId, pageIndex);
    }

    /** Replaces the page's areas with the submitted list (the browser owns the list). */
    @PutMapping("/documents/{documentId}/pages/{pageIndex}/redactions")
    public ResponseEntity<Void> saveRedactions(@PathVariable String documentId, @PathVariable int pageIndex,
            @RequestBody List<Redaction> areas) {
        checkPage(documentId, pageIndex);
        store.setRedactions(documentId, pageIndex, checked(areas));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/personal-info")
    public List<PersonalInfoMatch> findPersonalInfo(@RequestBody PersonalInfoRequest request) throws IOException {
        Set<PersonalInfoFinder.Kind> kinds = EnumSet.noneOf(PersonalInfoFinder.Kind.class);
        for (String name : request.kinds() == null ? List.<String>of() : request.kinds()) {
            try {
                kinds.add(PersonalInfoFinder.Kind.valueOf(name));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new InvalidEditException("알 수 없는 개인정보 종류입니다: " + name);
            }
        }
        String term = request.term() == null ? "" : request.term().strip();
        if (term.length() > 200) {
            throw new InvalidEditException("찾는 글자는 200자까지 쓸 수 있습니다.");
        }
        List<PersonalInfoMatch> found = new ArrayList<>();
        if (request.pages() == null || (kinds.isEmpty() && term.isEmpty())) {
            return found;
        }
        // Each document is read once, however many of its pages are asked about.
        Map<String, List<Integer>> pagesByDocument = new LinkedHashMap<>();
        for (PageSpec page : request.pages()) {
            checkPage(page.documentId(), page.pageIndex());
            List<Integer> pages = pagesByDocument.computeIfAbsent(page.documentId(), k -> new ArrayList<>());
            if (!pages.contains(page.pageIndex())) {
                pages.add(page.pageIndex());
            }
        }
        for (Map.Entry<String, List<Integer>> document : pagesByDocument.entrySet()) {
            try (PDDocument doc = Loader.loadPDF(store.getContent(document.getKey()))) {
                for (int pageIndex : document.getValue()) {
                    for (PersonalInfoFinder.Match m : PersonalInfoFinder.find(doc, pageIndex, kinds, term)) {
                        if (found.size() >= MAX_MATCHES) {
                            return found;
                        }
                        found.add(new PersonalInfoMatch(document.getKey(), pageIndex, m.kind().name(),
                                m.kind().label(), m.text(), m.x(), m.y(), m.width(), m.height()));
                    }
                }
            }
        }
        return found;
    }

    private void checkPage(String documentId, int pageIndex) {
        int pageCount = store.getInfo(documentId).pageCount();
        if (pageIndex < 0 || pageIndex >= pageCount) {
            throw new IndexOutOfBoundsException(String.valueOf(pageIndex));
        }
    }

    private static List<Redaction> checked(List<Redaction> areas) {
        if (areas == null) {
            return List.of();
        }
        if (areas.size() > MAX_AREAS) {
            throw new InvalidEditException("한 페이지에는 가림 영역을 " + MAX_AREAS + "개까지 둘 수 있습니다.");
        }
        for (Redaction area : areas) {
            boolean sane = area != null && area.id() != null && !area.id().isBlank() && area.id().length() <= 64
                    && finite(area.x()) && finite(area.y()) && area.width() > 0 && area.height() > 0
                    && area.width() <= MAX_COORDINATE && area.height() <= MAX_COORDINATE;
            if (!sane) {
                throw new InvalidEditException("가림 영역의 위치나 크기가 올바르지 않습니다.");
            }
        }
        return areas;
    }

    private static boolean finite(float value) {
        return Float.isFinite(value) && Math.abs(value) <= MAX_COORDINATE;
    }
}
