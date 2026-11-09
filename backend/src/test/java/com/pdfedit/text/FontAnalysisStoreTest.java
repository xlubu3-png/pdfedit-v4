package com.pdfedit.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pdfedit.dto.TextRunDto;
import com.pdfedit.service.TestPdfs;
import com.pdfedit.service.TextEditService;

/** What was learned about a page's fonts is kept on disk, so it is not learned again after a restart. */
class FontAnalysisStoreTest {

    @TempDir
    Path dir;

    private final FontMatcher matcher = new FontMatcher();

    private TextRunDto firstRun() throws IOException {
        return new TextEditService(matcher).describePage(TestPdfs.type3Line("ABAB"), 0, Map.of()).runs().get(0);
    }

    @Test
    void theResultsAreSavedReadBackAfterARestartAndUsedAsTheyWere() throws IOException {
        TypefaceChooser.forget();
        TextRunDto before = firstRun();
        int learned = TypefaceChooser.known();
        assertThat(learned).isPositive();

        FontAnalysisStore store = new FontAnalysisStore(dir.resolve(FontAnalysisStore.FILE), matcher);
        store.save();
        assertThat(dir.resolve(FontAnalysisStore.FILE)).isRegularFile();
        long written = Files.getLastModifiedTime(dir.resolve(FontAnalysisStore.FILE)).toMillis();

        // A restart: nothing in memory, the file read back.
        TypefaceChooser.forget();
        assertThat(new FontAnalysisStore(dir.resolve(FontAnalysisStore.FILE), matcher).load()).isEqualTo(learned);
        TextRunDto after = firstRun();
        assertThat(after.fontFamily()).isEqualTo(before.fontFamily());
        assertThat(after.matchSimilarity()).isEqualTo(before.matchSimilarity());
        assertThat(TypefaceChooser.known()).as("nothing had to be learned again").isEqualTo(learned);

        // Nothing new learned: the file is left as it is.
        store.save();
        assertThat(Files.getLastModifiedTime(dir.resolve(FontAnalysisStore.FILE)).toMillis()).isEqualTo(written);
    }

    @Test
    void resultsLearnedFromOtherFontsOrADamagedFileAreNotRead() throws IOException {
        TypefaceChooser.forget();
        firstRun();
        Path file = dir.resolve(FontAnalysisStore.FILE);
        TypefaceChooser.save(file, "other fonts");
        TypefaceChooser.forget();

        assertThat(new FontAnalysisStore(file, matcher).load()).isZero();

        Files.write(file, new byte[] {1, 2, 3});
        assertThat(new FontAnalysisStore(file, matcher).load()).isZero();
        assertThat(new FontAnalysisStore(dir.resolve("missing.bin"), matcher).load()).isZero();
    }
}
