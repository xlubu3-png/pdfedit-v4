package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pdfedit.dto.UpdateInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** The update check and install, against a small local server that plays GitHub. */
class UpdateServiceTest {

    private static final byte[] INSTALLER = "pretend this is a Setup.exe".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path folder;

    private HttpServer github;
    private String base;
    private String latestJson;
    private int latestStatus;
    private byte[] served;
    private final List<Path> launched = new CopyOnWriteArrayList<>();
    private final CountDownLatch quit = new CountDownLatch(1);
    private final AtomicInteger apiCalls = new AtomicInteger();

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @BeforeEach
    void startFakeGithub() throws Exception {
        github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + github.getAddress().getPort();
        served = INSTALLER;
        latestStatus = 200;
        latestJson = release("v0.099", sha256(INSTALLER), true);
        byte[] sumsFile = (sha256(INSTALLER) + "  WINTECH_PDF_Setup_0.099.exe\n").getBytes(StandardCharsets.UTF_8);
        github.createContext("/repos/me/app/releases/latest", exchange -> {
            apiCalls.incrementAndGet();
            reply(exchange, latestStatus, latestJson.getBytes(StandardCharsets.UTF_8));
        });
        github.createContext("/dl/WINTECH_PDF_Setup_0.099.exe", exchange -> reply(exchange, 200, served));
        github.createContext("/dl/WINTECH_PDF_Setup_0.099.exe.sha256", exchange -> reply(exchange, 200, sumsFile));
        github.start();
    }

    @AfterEach
    void stopFakeGithub() {
        github.stop(0);
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    /** A "latest release" answer; {@code withDigest} puts the hash in the asset, otherwise a .sha256 file carries it. */
    private String release(String tag, String hash, boolean withDigest) {
        String digest = withDigest ? ",\"digest\":\"sha256:" + hash + "\"" : "";
        String sums = withDigest ? "" : ",{\"name\":\"WINTECH_PDF_Setup_0.099.exe.sha256\",\"browser_download_url\":\""
                + base + "/dl/WINTECH_PDF_Setup_0.099.exe.sha256\"}";
        return "{\"tag_name\":\"" + tag + "\",\"html_url\":\"" + base + "/releases/" + tag
                + "\",\"body\":\"New: font help\",\"assets\":[{\"name\":\"notes.txt\",\"browser_download_url\":\""
                + base + "/dl/notes.txt\"},{\"name\":\"WINTECH_PDF_Setup_0.099.exe\",\"browser_download_url\":\""
                + base + "/dl/WINTECH_PDF_Setup_0.099.exe\"" + digest + "}" + sums + "]}";
    }

    private UpdateService service(boolean enabled, String current) {
        return new UpdateService(enabled, current, "me/app", base, base + "/dl/", launched::add, quit::countDown,
                folder.resolve("downloads"));
    }

    @Test
    void versionsAreComparedNumberByNumber() {
        assertThat(UpdateService.compareVersions("0.052", "0.051")).isPositive();
        assertThat(UpdateService.compareVersions("0.100", "0.099")).isPositive();
        assertThat(UpdateService.compareVersions("v0.051", "0.051")).isZero();
        assertThat(UpdateService.compareVersions("0.050", "0.051")).isNegative();
        assertThat(UpdateService.compareVersions("1.0", "0.999")).isPositive();
    }

    @Test
    void aHashIsFoundInTheFormsGithubAndSha256sumWriteIt() {
        String hash = "a".repeat(64);

        assertThat(UpdateService.hashOf("sha256:" + hash)).isEqualTo(hash);
        assertThat(UpdateService.hashOf(hash.toUpperCase() + "  file.exe\n")).isEqualTo(hash);
        assertThat(UpdateService.hashOf("no hash here")).isNull();
        assertThat(UpdateService.hashOf(null)).isNull();
    }

    @Test
    void doesNothingWhenUpdatingIsSwitchedOff() {
        UpdateInfo info = service(false, "0.051").check(true);

        assertThat(info.enabled()).isFalse();
        assertThat(info.newer()).isFalse();
        assertThat(apiCalls).hasValue(0);
    }

    @Test
    void reportsANewerRelease() {
        UpdateInfo info = service(true, "0.051").check(false);

        assertThat(info.enabled()).isTrue();
        assertThat(info.newer()).isTrue();
        assertThat(info.latest()).isEqualTo("0.099");
        assertThat(info.notes()).isEqualTo("New: font help");
        assertThat(info.releaseUrl()).endsWith("/releases/v0.099");
        assertThat(info.error()).isNull();
    }

    @Test
    void theSameOrAnOlderReleaseIsNotAnUpdate() {
        assertThat(service(true, "0.099").check(false).newer()).isFalse();
        assertThat(service(true, "0.120").check(false).newer()).isFalse();
    }

    @Test
    void aRepositoryWithoutReleasesIsNotAnError() {
        latestStatus = 404;

        UpdateInfo info = service(true, "0.051").check(false);

        assertThat(info.newer()).isFalse();
        assertThat(info.error()).isNull();
    }

    @Test
    void anUnreachableOrBrokenGithubIsReportedWithoutThrowing() {
        latestStatus = 500;
        UpdateInfo broken = service(true, "0.051").check(false);
        assertThat(broken.newer()).isFalse();
        assertThat(broken.error()).isNotBlank();

        github.stop(0);
        UpdateInfo gone = service(true, "0.051").check(false);
        assertThat(gone.newer()).isFalse();
        assertThat(gone.error()).isNotBlank();
    }

    @Test
    void theAnswerIsRememberedUntilARefreshIsAskedFor() {
        UpdateService updates = service(true, "0.051");

        updates.check(false);
        updates.check(false);
        assertThat(apiCalls).hasValue(1);

        updates.check(true);
        assertThat(apiCalls).hasValue(2);
    }

    @Test
    void installDownloadsChecksStartsTheInstallerAndQuits() throws Exception {
        service(true, "0.051").install();

        assertThat(launched).hasSize(1);
        assertThat(Files.readAllBytes(launched.get(0))).isEqualTo(INSTALLER);
        assertThat(launched.get(0).getFileName().toString()).isEqualTo("WINTECH_PDF_Setup_0.099.exe");
        assertThat(quit.await(5, TimeUnit.SECONDS)).as("the app quits after answering").isTrue();
    }

    @Test
    void theHashMayComeFromASha256FileNextToTheInstaller() throws Exception {
        latestJson = release("v0.099", "unused", false);

        service(true, "0.051").install();

        assertThat(launched).hasSize(1);
    }

    @Test
    void aFileThatDoesNotMatchItsPublishedHashIsNeverStarted() throws Exception {
        served = "tampered".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service(true, "0.051").install())
                .isInstanceOf(UpdateFailedException.class)
                .hasMessageContaining("검증값");

        assertThat(launched).isEmpty();
        assertThat(quit.getCount()).isEqualTo(1);
        try (var files = Files.list(folder.resolve("downloads"))) {
            assertThat(files).as("nothing is left behind").isEmpty();
        }
    }

    @Test
    void aReleaseWithoutAHashIsRefused() {
        latestJson = release("v0.099", "unused", false)
                .replaceAll(",\\{\"name\":\"WINTECH_PDF_Setup_0.099.exe.sha256\".*?\\}", "");

        assertThatThrownBy(() -> service(true, "0.051").install())
                .isInstanceOf(UpdateFailedException.class)
                .hasMessageContaining("해시");
        assertThat(launched).isEmpty();
    }

    @Test
    void aDownloadAddressOutsideTheRepositoryIsRefused() throws Exception {
        latestJson = release("v0.099", sha256(INSTALLER), true).replace(base + "/dl/WINTECH_PDF_Setup_0.099.exe\"",
                "http://evil.example/WINTECH_PDF_Setup_0.099.exe\"");

        assertThatThrownBy(() -> service(true, "0.051").install())
                .isInstanceOf(UpdateFailedException.class)
                .hasMessageContaining("저장소");
        assertThat(launched).isEmpty();
    }

    @Test
    void nothingIsInstalledWhenThereIsNothingNewer() {
        assertThatThrownBy(() -> service(true, "0.099").install())
                .isInstanceOf(UpdateFailedException.class)
                .hasMessageContaining("새 버전이 없습니다");
        assertThat(launched).isEmpty();
    }

    @Test
    void installIsRefusedWhenUpdatingIsSwitchedOff() {
        assertThatThrownBy(() -> service(false, "0.051").install()).isInstanceOf(UpdateFailedException.class);
        assertThat(launched).isEmpty();
    }
}
