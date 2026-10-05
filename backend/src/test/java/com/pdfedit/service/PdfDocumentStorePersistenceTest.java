package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pdfedit.text.AddedText;
import com.pdfedit.text.RunEdit;

/** Documents and their edits must survive a restart of the app (a new store on the same folder). */
class PdfDocumentStorePersistenceTest {

    @TempDir
    Path folder;

    @Test
    void aDocumentItsEditsAndAddedTextsComeBackAfterARestart() throws IOException {
        byte[] pdf = TestPdfs.koreanPage();
        PdfDocumentStore first = new PdfDocumentStore(folder, Duration.ofDays(14));
        String id = first.store("계약서.pdf", pdf, 1);
        first.pageEdits(id, 0).put(1, new RunEdit("Hold World", "Batang", 18f, true, "#336699", 10.5f, -4f));
        first.persist(id);
        first.setAddedTexts(id, 0, List.of(new AddedText("a1", "새 글자\n두 줄", 100f, 400f, null, 14f, false, "#ff0000")));

        PdfDocumentStore restarted = new PdfDocumentStore(folder, Duration.ofDays(14));

        assertThat(restarted.getInfo(id)).isEqualTo(new PdfDocumentStore.DocumentInfo("계약서.pdf", 1));
        assertThat(restarted.getContent(id)).isEqualTo(pdf);
        PageEdits edits = restarted.pageEditsSnapshot(id, 0);
        assertThat(edits.runs().get(1)).isEqualTo(new RunEdit("Hold World", "Batang", 18f, true, "#336699", 10.5f, -4f));
        assertThat(edits.added()).containsExactly(new AddedText("a1", "새 글자\n두 줄", 100f, 400f, null, 14f, false, "#ff0000"));
    }

    @Test
    void resettingAPageIsRememberedToo() throws IOException {
        PdfDocumentStore first = new PdfDocumentStore(folder, Duration.ofDays(14));
        String id = first.store("a.pdf", TestPdfs.koreanPage(), 1);
        first.pageEdits(id, 0).put(0, RunEdit.ofText("x"));
        first.persist(id);
        first.clearPageEdits(id, 0);

        PageEdits edits = new PdfDocumentStore(folder, Duration.ofDays(14)).pageEditsSnapshot(id, 0);

        assertThat(edits.isEmpty()).isTrue();
    }

    @Test
    void documentsNobodyTouchedForTheRetentionPeriodAreDeletedWhenTheAppStarts() throws IOException {
        PdfDocumentStore first = new PdfDocumentStore(folder, Duration.ofDays(14));
        String old = first.store("old.pdf", TestPdfs.koreanPage(), 1);
        String fresh = first.store("fresh.pdf", TestPdfs.koreanPage(), 1);
        Files.setLastModifiedTime(folder.resolve(old + ".json"), FileTime.from(Instant.now().minus(Duration.ofDays(20))));

        PdfDocumentStore restarted = new PdfDocumentStore(folder, Duration.ofDays(14));

        assertThat(restarted.getInfo(fresh).fileName()).isEqualTo("fresh.pdf");
        assertThatThrownBy(() -> restarted.getInfo(old)).isInstanceOf(NoSuchElementException.class);
        assertThat(folder.resolve(old + ".pdf")).doesNotExist();
        assertThat(folder.resolve(old + ".json")).doesNotExist();
    }

    @Test
    void anUnreadableSavedDocumentIsSkippedNotFatal() throws IOException {
        Files.writeString(folder.resolve("broken.json"), "{ this is not json");
        Files.write(folder.resolve("broken.pdf"), new byte[] {1, 2, 3});

        PdfDocumentStore store = new PdfDocumentStore(folder, Duration.ofDays(14));

        assertThatThrownBy(() -> store.getInfo("broken")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void anIdCanNeverNameAFileOutsideTheDataFolder() {
        PdfDocumentStore store = new PdfDocumentStore(folder, Duration.ofDays(14));

        assertThatThrownBy(() -> store.getContent("../../secret")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void withoutADataFolderNothingIsWritten() throws IOException {
        PdfDocumentStore memoryOnly = new PdfDocumentStore();

        String id = memoryOnly.store("a.pdf", TestPdfs.koreanPage(), 1);
        memoryOnly.persist(id);

        assertThat(memoryOnly.getInfo(id).fileName()).isEqualTo("a.pdf");
        try (var files = Files.list(folder)) {
            assertThat(files).isEmpty();
        }
    }
}
