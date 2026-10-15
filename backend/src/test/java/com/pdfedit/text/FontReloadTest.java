package com.pdfedit.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A font installed while the app runs is found after a reload; a font the PC lacks is reported as missing. */
class FontReloadTest {

    @TempDir
    Path folder;

    /** A standalone TrueType font installed on this machine, or the test is skipped. */
    private static SystemFonts.Face installedFont() {
        for (String name : List.of("malgungothic", "nanumgothic", "dotum")) {
            Optional<SystemFonts.Face> face = SystemFonts.system().find(false, name).filter(f -> !f.inCollection());
            if (face.isPresent()) {
                return face.get();
            }
        }
        return abort("no standalone TrueType font installed on this machine");
    }

    @Test
    void thePdfNameIsReducedToTheNamesAnInstalledFontCouldCarry() {
        assertThat(SystemFonts.pdfNameCandidates("ABCDEF+HYwulM-Bold")).contains("hywulmbold", "hywulm");
        assertThat(SystemFonts.pdfNameCandidates("Arial,Bold")).contains("arialbold", "arial");
        assertThat(SystemFonts.pdfNameCandidates("TimesNewRomanPSMT")).contains("timesnewromanpsmt", "timesnewroman");
        assertThat(SystemFonts.pdfNameCandidates("ABCDEF+HCR Batang")).contains("hcrbatang");
        assertThat(SystemFonts.pdfNameCandidates(null)).isEmpty();
        assertThat(SystemFonts.pdfNameCandidates("  ")).isEmpty();
    }

    @Test
    void aFontAddedToTheFontFolderIsFoundOnlyAfterAReload() throws IOException {
        SystemFonts.Face face = installedFont();
        SystemFonts fonts = new SystemFonts(List.of(folder));
        assertThat(fonts.count()).isZero();

        Files.copy(face.file(), folder.resolve(face.file().getFileName()));

        assertThat(fonts.count()).as("the folder was read once and is remembered").isZero();
        fonts.reload();
        assertThat(fonts.count()).isGreaterThan(0);
    }

    @Test
    void aSubsetFontIsFoundByItsPdfNameOnceTheFontIsInstalled() throws IOException {
        SystemFonts.Face face = installedFont();
        try (PDDocument doc = new PDDocument(); InputStream in = face.openStandalone()) {
            PDType0Font page = PDType0Font.load(doc, in, true);
            FontMatcher matcher = new FontMatcher(new SystemFonts(List.of(folder)));

            assertThat(matcher.isMissing(page)).as("not installed yet").isTrue();

            Files.copy(face.file(), folder.resolve(face.file().getFileName()));
            assertThat(matcher.reloadFonts()).isGreaterThan(0);

            assertThat(matcher.isMissing(page)).as("installed and reloaded").isFalse();
            assertThat(matcher.matchFor(page, RunPainter.PageGlyphs.of(List.of())).cssFamily).isEqualTo(face.family());
        }
    }

    @Test
    void namesAProducerMadeUpAreNotFontsToInstall() {
        assertThat(FontMatcher.isPlaceholderName("CIDFont+F1")).isTrue();
        assertThat(FontMatcher.isPlaceholderName("CIDFont+F2")).isTrue();
        assertThat(FontMatcher.isPlaceholderName("ABCDEF+CIDFont+F1")).isTrue();
        assertThat(FontMatcher.isPlaceholderName("F3")).isTrue();
        assertThat(FontMatcher.isPlaceholderName("ABCDEF+Font12")).isTrue();

        assertThat(FontMatcher.isPlaceholderName("ABCDEF+HYwulM-Bold")).isFalse();
        assertThat(FontMatcher.isPlaceholderName("Arial,Bold")).isFalse();
        assertThat(FontMatcher.isPlaceholderName("ABCDEF+HCR Batang")).isFalse();
        assertThat(FontMatcher.isPlaceholderName("F1Gothic")).isFalse();
    }

    @Test
    void fontsWithNothingToInstallAreNeverReportedMissing() {
        FontMatcher matcher = new FontMatcher(new SystemFonts(List.of(folder)));

        assertThat(matcher.isMissing(null)).isFalse();
        assertThat(matcher.isMissing(new PDType1Font(Standard14Fonts.FontName.HELVETICA))).isFalse();
    }
}
