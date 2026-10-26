package com.pdfedit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pdfedit.dto.AppSettings;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.TestPdfs;

class SettingsControllerTest {

    @Test
    void theRetentionComesFromTheConfigurationUntilTheUserChoosesOne(@TempDir Path folder) {
        SettingsController settings = new SettingsController(new PdfDocumentStore(folder, Duration.ofDays(30)));

        assertThat(settings.settings()).isEqualTo(new AppSettings(30));
    }

    @Test
    void aChosenRetentionIsUsedAndOutlastsARestart(@TempDir Path folder) {
        SettingsController settings = new SettingsController(new PdfDocumentStore(folder, Duration.ofDays(30)));

        assertThat(settings.save(new AppSettings(90))).isEqualTo(new AppSettings(90));

        PdfDocumentStore restarted = new PdfDocumentStore(folder, Duration.ofDays(30));
        assertThat(restarted.retentionDays()).isEqualTo(90);
    }

    @Test
    void theSettingsFileIsNotMistakenForADocument(@TempDir Path folder) throws IOException {
        PdfDocumentStore store = new PdfDocumentStore(folder, Duration.ofDays(30));
        String id = store.store("a.pdf", TestPdfs.koreanPage(), 1);
        store.setRetentionDays(365);

        new PdfDocumentStore(folder, Duration.ofDays(30));

        try (var files = Files.list(folder)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .contains("settings.properties", id + ".pdf", id + ".json");
        }
    }

    @Test
    void anImpossibleRetentionIsRefused(@TempDir Path folder) {
        SettingsController settings = new SettingsController(new PdfDocumentStore(folder, Duration.ofDays(30)));

        assertThatThrownBy(() -> settings.save(new AppSettings(0))).isInstanceOf(InvalidEditException.class);
        assertThatThrownBy(() -> settings.save(new AppSettings(5000))).isInstanceOf(InvalidEditException.class);
        assertThat(settings.settings()).isEqualTo(new AppSettings(30));
    }

    @Test
    void aBrokenSettingsFileFallsBackToTheConfiguration(@TempDir Path folder) throws IOException {
        Files.writeString(folder.resolve("settings.properties"), "retentionDays=soon\n");

        assertThat(new PdfDocumentStore(folder, Duration.ofDays(30)).retentionDays()).isEqualTo(30);
    }
}
