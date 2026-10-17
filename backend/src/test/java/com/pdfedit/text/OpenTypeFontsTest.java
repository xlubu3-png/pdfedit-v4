package com.pdfedit.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pdfedit.controller.AppInfoController;
import com.pdfedit.dto.AppInfo;

/**
 * .otf files: PDFBox can read the name of every one but can only put one with TrueType outlines into a PDF.
 * The others are not used, and the app says so instead of letting them silently go missing.
 */
class OpenTypeFontsTest {

    /** A font with CFF outlines that happens to ship with common developer tools; the tests are skipped without it. */
    private static final Path CFF_OTF = Path.of("C:/Program Files/JetBrains/PyCharm 2026.2.3/jbr/lib/fonts/Inter-Regular.otf");

    @TempDir
    Path folder;

    private static SystemFonts.Face installedTrueTypeFont() {
        for (String name : List.of("malgungothic", "nanumgothic", "dotum")) {
            Optional<SystemFonts.Face> face = SystemFonts.system().find(false, name).filter(f -> !f.inCollection());
            if (face.isPresent()) {
                return face.get();
            }
        }
        return abort("no standalone TrueType font installed on this machine");
    }

    @Test
    void anOtfFileWithTrueTypeOutlinesIsAnOrdinaryFont() throws IOException {
        SystemFonts.Face face = installedTrueTypeFont();
        // the same font under the .otf extension: what matters is what is inside, not what it is called
        Files.copy(face.file(), folder.resolve("Renamed.otf"));

        SystemFonts fonts = new SystemFonts(List.of(folder));

        assertThat(fonts.count()).isEqualTo(1);
        assertThat(fonts.unreadableFamilies()).isEmpty();
    }

    @Test
    void anOtfFileWithCffOutlinesIsNotUsedButIsReportedByName() throws IOException {
        assumeTrue(Files.exists(CFF_OTF), "no CFF-based .otf font on this machine");
        Files.copy(CFF_OTF, folder.resolve("Inter-Regular.otf"));

        SystemFonts fonts = new SystemFonts(List.of(folder));

        assertThat(fonts.count()).as("not an installed font the app can draw with").isZero();
        assertThat(fonts.unreadableFamilies()).containsExactly("Inter");
    }

    @Test
    void theReportComesWithTheInfoTheScreenAsksFor() throws IOException {
        assumeTrue(Files.exists(CFF_OTF), "no CFF-based .otf font on this machine");
        Files.copy(CFF_OTF, folder.resolve("Inter-Regular.otf"));

        AppInfo info = new AppInfoController("0.1", new FontMatcher(new SystemFonts(List.of(folder)))).info();

        assertThat(info.installedFonts()).isZero();
        assertThat(info.unreadableFonts()).containsExactly("Inter");
    }

    @Test
    void aFolderWithoutOtfFilesReportsNothing() {
        assertThat(new SystemFonts(List.of(folder)).unreadableFamilies()).isEmpty();
    }

    @Test
    void aBrokenOtfFileIsSkippedWithoutFuss() throws IOException {
        Files.writeString(folder.resolve("broken.otf"), "not a font");

        SystemFonts fonts = new SystemFonts(List.of(folder));

        assertThat(fonts.count()).isZero();
        assertThat(fonts.unreadableFamilies()).isEmpty();
    }
}
