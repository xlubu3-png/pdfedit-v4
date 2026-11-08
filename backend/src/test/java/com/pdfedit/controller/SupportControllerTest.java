package com.pdfedit.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.TestPdfs;
import com.pdfedit.text.FontMatcher;

/** The problem report holds the logs and a page about the PC and the program, and nothing of the documents. */
class SupportControllerTest {

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                files.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    void theLogsAndThePcAreInItAndTheDocumentsAreNot(@TempDir Path folder) throws Exception {
        Path logs = Files.createDirectories(folder.resolve("logs"));
        Files.writeString(logs.resolve("app.log"), "INFO Started PdfEditApplication\nERROR something failed\n");
        Files.writeString(logs.resolve("app.log.2026-11-01.0.gz"), "older");
        Files.writeString(logs.resolve("other.txt"), "not a log");
        PdfDocumentStore store = new PdfDocumentStore(folder.resolve("data"), Duration.ofDays(30));
        store.store("비밀 계약서.pdf", TestPdfs.koreanPage(), 1);
        MockEnvironment env = new MockEnvironment().withProperty("logging.file.name", logs.resolve("app.log").toString());

        ResponseEntity<byte[]> answer = new SupportController(store, new FontMatcher(), env, "0.137").report();

        assertThat(answer.getHeaders().getContentDisposition().getFilename()).startsWith("WINTECH_문제보고_").endsWith(".zip");
        Map<String, String> files = unzip(answer.getBody());
        assertThat(files).containsKeys("PC와 프로그램 정보.txt", "기록/app.log", "기록/app.log.2026-11-01.0.gz");
        assertThat(files).doesNotContainKey("기록/other.txt");
        assertThat(files.get("기록/app.log")).contains("ERROR something failed");
        String about = files.get("PC와 프로그램 정보.txt");
        assertThat(about).contains("프로그램 버전: 0.137").contains("운영체제: ").contains("보관 중인 문서 수: 1");
        assertThat(String.join("", files.values())).doesNotContain("비밀 계약서").doesNotContain("%PDF");
    }

    @Test
    void withoutALogFileItSaysSo(@TempDir Path folder) throws Exception {
        Map<String, String> files = unzip(new SupportController(new PdfDocumentStore(), new FontMatcher(),
                new MockEnvironment(), "0.137").report().getBody());
        assertThat(files).containsKeys("PC와 프로그램 정보.txt", "기록 파일 없음.txt");
    }
}
