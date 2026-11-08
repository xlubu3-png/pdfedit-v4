package com.pdfedit.controller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.text.FontMatcher;

/**
 * 문제 보고: what someone looking into a problem needs, in one ZIP the user can send - the app's log files and a
 * page about this PC and this copy of the program (versions, Windows, memory, fonts, settings). No document and
 * nothing of what is in one goes into it.
 */
@RestController
@RequestMapping("/api/v1/pdf/support")
public class SupportController {

    /** A log file bigger than this is cut to its end: the newest part is the one that matters. */
    private static final long MAX_LOG_BYTES = 20L * 1024 * 1024;
    /** At most this many earlier log files (rolled over by date or size) come along. */
    private static final int MAX_OLD_LOGS = 5;

    private final PdfDocumentStore store;
    private final FontMatcher fonts;
    private final Environment environment;
    private final String version;

    public SupportController(PdfDocumentStore store, FontMatcher fonts, Environment environment,
            @Value("${app.version}") String version) {
        this.store = store;
        this.fonts = fonts;
        this.environment = environment;
        this.version = version;
    }

    @GetMapping("/report")
    public ResponseEntity<byte[]> report() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "PC와 프로그램 정보.txt", about(now).getBytes(StandardCharsets.UTF_8));
            List<Path> logs = logFiles();
            if (logs.isEmpty()) {
                put(zip, "기록 파일 없음.txt",
                        "이 실행 방식에서는 기록 파일(app.log)을 남기지 않습니다 (설치본에서만 남김).\n"
                                .getBytes(StandardCharsets.UTF_8));
            }
            for (Path log : logs) {
                put(zip, "기록/" + log.getFileName(), tail(log));
            }
        }
        String name = "WINTECH_문제보고_" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"))
                .body(bytes.toByteArray());
    }

    /** The page about this PC and this copy of the program. */
    String about(LocalDateTime now) {
        Runtime runtime = Runtime.getRuntime();
        StringBuilder out = new StringBuilder();
        line(out, "만든 때", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        line(out, "프로그램 버전", version);
        line(out, "설치본", String.valueOf(System.getProperty("jpackage.app-path") != null));
        line(out, "Java", System.getProperty("java.version") + " (" + System.getProperty("java.vm.name") + ", "
                + System.getProperty("java.vm.info") + ")");
        line(out, "운영체제", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        line(out, "언어·지역", Locale.getDefault().toLanguageTag() + ", 문자 " + System.getProperty("file.encoding"));
        line(out, "CPU 수", String.valueOf(runtime.availableProcessors()));
        line(out, "메모리 (쓰는 중 / 잡아 둔 / 최대)", mb(runtime.totalMemory() - runtime.freeMemory()) + " / "
                + mb(runtime.totalMemory()) + " / " + mb(runtime.maxMemory()));
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            line(out, "PC 메모리 (전체 / 남은)", mb(os.getTotalMemorySize()) + " / " + mb(os.getFreeMemorySize()));
        }
        line(out, "켜진 지", ManagementFactory.getRuntimeMXBean().getUptime() / 1000 + "초");
        line(out, "Java 옵션", String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()));
        line(out, "설치된 글꼴 수", String.valueOf(fonts.installedFontCount()));
        line(out, "보관 중인 문서 수", String.valueOf(store.documentCount()));
        line(out, "설정", store.settingsForReport().toString());
        line(out, "데이터 폴더", environment.getProperty("app.data-dir", ""));
        line(out, "기록 파일", environment.getProperty("logging.file.name", "(없음)"));
        Path aot = Path.of(System.getProperty("user.home"), "WINTECH_PDF", "app.aot");
        line(out, "빨리 켜기 준비 파일", Files.exists(aot) ? aot + " (" + mbOf(aot) + ")" : "없음");
        return out.toString();
    }

    /** The app's log file and the newest of those rolled over from it, newest first. */
    private List<Path> logFiles() {
        String name = environment.getProperty("logging.file.name");
        if (name == null || name.isBlank()) {
            return List.of();
        }
        Path log = Path.of(name).toAbsolutePath();
        Path dir = log.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        String base = log.getFileName().toString();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> found = files.filter(p -> p.getFileName().toString().startsWith(base) && Files.isRegularFile(p))
                    .sorted((a, b) -> Long.compare(modified(b), modified(a)))
                    .toList();
            return found.subList(0, Math.min(found.size(), MAX_OLD_LOGS + 1));
        } catch (IOException e) {
            return List.of();
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** The file, or its last {@link #MAX_LOG_BYTES} when it is longer. */
    private static byte[] tail(Path file) throws IOException {
        long size = Files.size(file);
        try (InputStream in = Files.newInputStream(file)) {
            if (size > MAX_LOG_BYTES) {
                in.skipNBytes(size - MAX_LOG_BYTES);
            }
            return in.readAllBytes();
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static void line(StringBuilder out, String what, String value) {
        out.append(what).append(": ").append(value).append("\r\n");
    }

    private static String mb(long bytes) {
        return bytes / (1024 * 1024) + " MB";
    }

    private static String mbOf(Path file) {
        try {
            return mb(Files.size(file));
        } catch (IOException e) {
            return "?";
        }
    }
}
