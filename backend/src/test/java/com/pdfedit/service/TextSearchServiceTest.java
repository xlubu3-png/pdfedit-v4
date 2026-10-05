package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pdfedit.dto.FindMatch;
import com.pdfedit.dto.PageSpec;
import com.pdfedit.dto.ReplaceResult;
import com.pdfedit.dto.TextRunDto;
import com.pdfedit.text.FontMatcher;
import com.pdfedit.text.RunEdit;

class TextSearchServiceTest {

    private final PdfDocumentStore store = new PdfDocumentStore();
    private final TextEditService editService = new TextEditService(new FontMatcher());
    private final TextSearchService search = new TextSearchService(store, editService);

    private String id;
    private List<PageSpec> pages;

    private void upload() throws IOException {
        id = store.store("a.pdf", TestPdfs.koreanPage(), 1);
        pages = List.of(new PageSpec(id, 0, 0));
    }

    @Test
    void findsTheLinesContainingTheTextIgnoringCaseUnlessAsked() throws IOException {
        upload();

        List<FindMatch> any = search.find(pages, "hello", false);
        List<FindMatch> exact = search.find(pages, "hello", true);

        assertThat(any).hasSize(1);
        assertThat(any.get(0).documentId()).isEqualTo(id);
        assertThat(any.get(0).text()).startsWith("Hello");
        assertThat(exact).isEmpty();
        assertThat(search.find(pages, "Hello", true)).hasSize(1);
    }

    @Test
    void theSamePageListedTwiceIsSearchedOnce() throws IOException {
        upload();

        assertThat(search.find(List.of(pages.get(0), pages.get(0)), "Hello", true)).hasSize(1);
    }

    @Test
    void replaceChangesTheLineAndTheNewTextCanBeFoundAgain() throws IOException {
        upload();

        ReplaceResult result = search.replace(pages, "Hello", "Howdy", true);

        assertThat(result.replacements()).isEqualTo(1);
        assertThat(result.runsChanged()).isEqualTo(1);
        assertThat(result.pages()).containsExactly(new PageSpec(id, 0, 0));
        assertThat(result.skipped()).isNull();
        assertThat(search.find(pages, "Howdy", true)).hasSize(1);
        assertThat(search.find(pages, "Hello", true)).isEmpty();
        TextRunDto line = editService.describePage(store.getContent(id), 0, store.pageEditsSnapshot(id, 0))
                .runs().stream().filter(r -> r.edited()).findFirst().orElseThrow();
        assertThat(line.currentText()).startsWith("Howdy");
    }

    @Test
    void replaceKeepsWhatTheUserAlreadyDidToTheLine() throws IOException {
        upload();
        int index = search.find(pages, "Hello", true).get(0).runIndex();
        store.pageEdits(id, 0).put(index, new RunEdit("Hello World", "Batang", 18f, true, "#336699", 12f, -3f));

        search.replace(pages, "Hello", "Howdy", true);

        RunEdit edit = store.pageEditsSnapshot(id, 0).runs().get(index);
        assertThat(edit.text()).isEqualTo("Howdy World");
        assertThat(edit.fontFamily()).isEqualTo("Batang");
        assertThat(edit.fontSize()).isEqualTo(18f);
        assertThat(edit.bold()).isTrue();
        assertThat(edit.color()).isEqualTo("#336699");
        assertThat(edit.dx()).isEqualTo(12f);
        assertThat(edit.dy()).isEqualTo(-3f);
    }

    @Test
    void replacingTextThatIsNotThereChangesNothing() throws IOException {
        upload();

        ReplaceResult result = search.replace(pages, "no such text", "x", true);

        assertThat(result.replacements()).isZero();
        assertThat(result.pages()).isEmpty();
        assertThat(store.pageEditsSnapshot(id, 0).isEmpty()).isTrue();
    }

    @Test
    void replacingBackRestoresTheOriginalAndDropsTheEdit() throws IOException {
        upload();

        search.replace(pages, "Hello", "Howdy", true);
        search.replace(pages, "Howdy", "Hello", true);

        assertThat(store.pageEditsSnapshot(id, 0).runs()).isEmpty();
    }

    @Test
    void anEmptySearchIsRefused() throws IOException {
        upload();

        assertThatThrownBy(() -> search.find(pages, "", false)).isInstanceOf(InvalidEditException.class);
        assertThatThrownBy(() -> search.replace(pages, null, "x", false)).isInstanceOf(InvalidEditException.class);
    }

    @Test
    void regexCharactersAreSearchedLiterally() throws IOException {
        upload();

        assertThat(search.find(pages, "H.llo", false)).isEmpty();
        assertThat(search.find(pages, "(", false)).isEmpty();
    }
}
