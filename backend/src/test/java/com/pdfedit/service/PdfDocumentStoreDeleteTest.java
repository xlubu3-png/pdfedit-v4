package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** "모두 지우기" must really remove the stored copy of a document, not just forget it in the browser. */
class PdfDocumentStoreDeleteTest {

    @TempDir
    Path folder;

    @Test
    void deletingADocumentRemovesItFromMemoryAndFromTheDataFolder() throws IOException {
        PdfDocumentStore store = new PdfDocumentStore(folder, Duration.ofDays(14));
        String id = store.store("계약서.pdf", TestPdfs.koreanPage(), 1);
        String other = store.store("남길 문서.pdf", TestPdfs.koreanPage(), 1);
        assertThat(folder.resolve(id + ".pdf")).exists();

        store.delete(id);

        assertThatThrownBy(() -> store.getInfo(id)).isInstanceOf(NoSuchElementException.class);
        assertThat(folder.resolve(id + ".pdf")).doesNotExist();
        assertThat(folder.resolve(id + ".json")).doesNotExist();
        assertThat(store.getInfo(other).fileName()).isEqualTo("남길 문서.pdf");
        assertThat(new PdfDocumentStore(folder, Duration.ofDays(14)).getInfo(other).pageCount()).isEqualTo(1);
    }

    @Test
    void deletingAnUnknownDocumentIsHarmless() {
        PdfDocumentStore store = new PdfDocumentStore(folder, Duration.ofDays(14));

        store.delete("no-such-id");
        store.delete("../../etc/passwd");
    }
}
