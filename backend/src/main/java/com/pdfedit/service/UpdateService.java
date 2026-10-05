package com.pdfedit.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import com.pdfedit.dto.UpdateInfo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds out whether a newer version of the app has been published as a GitHub release, and installs it:
 * downloads the release's Setup.exe, checks it against the published SHA-256, starts it and quits, so the
 * installer can replace the files. Only the Windows installer build does this ({@code app.update.enabled}).
 *
 * <p>The release is the only source trusted: the download address must lie under the repository's own
 * release downloads, the file must match the hash the release publishes, and the browser never says what to
 * download - it only asks for "the update that was found".
 */
@Service
public class UpdateService {

    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);
    private static final Duration CACHE_FOR = Duration.ofHours(6);
    private static final Duration API_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);
    private static final long MAX_INSTALLER_BYTES = 400L * 1024 * 1024;
    private static final Pattern INSTALLER_NAME = Pattern.compile("(?i)^WINTECH_PDF_Setup_.*\\.exe$");
    private static final Pattern SHA256 = Pattern.compile("(?i)\\b([0-9a-f]{64})\\b");

    /** What is needed to install a release: where it is, what it must hash to. */
    record Candidate(String tag, String version, String releaseUrl, String notes, String fileName, String url,
                     String sha256) {
    }

    /** Starts the downloaded installer. */
    interface InstallerLauncher {
        void launch(Path installer) throws IOException;
    }

    private final boolean enabled;
    private final String currentVersion;
    private final String apiBase;
    private final String downloadPrefix;
    private final String repo;
    private final HttpClient http;
    private final InstallerLauncher launcher;
    private final Runnable quit;
    private final Path downloadDir;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicBoolean installing = new AtomicBoolean();

    private volatile Candidate latest;
    private volatile Instant checkedAt;
    private volatile String lastError;

    @Autowired
    public UpdateService(@Value("${app.update.enabled:false}") boolean enabled,
            @Value("${app.version}") String currentVersion,
            @Value("${app.update.repo:xlubu3-png/pdfedit-v4}") String repo,
            @Value("${app.update.api-base:https://api.github.com}") String apiBase,
            @Value("${app.update.download-prefix:}") String downloadPrefix,
            ConfigurableApplicationContext context) {
        this(enabled, currentVersion, repo, apiBase, downloadPrefix, UpdateService::startWithShell,
                () -> quitApp(context), Path.of(System.getProperty("java.io.tmpdir"), "WINTECH_PDF_update"));
    }

    UpdateService(boolean enabled, String currentVersion, String repo, String apiBase, String downloadPrefix,
            InstallerLauncher launcher, Runnable quit, Path downloadDir) {
        this.enabled = enabled;
        this.currentVersion = currentVersion;
        this.repo = repo;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.downloadPrefix = downloadPrefix == null || downloadPrefix.isBlank()
                ? "https://github.com/" + repo + "/releases/download/" : downloadPrefix;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5)).build();
        this.launcher = launcher;
        this.quit = quit;
        this.downloadDir = downloadDir;
    }

    /** Whether a newer version exists; the answer is remembered for a few hours unless {@code refresh}. */
    public UpdateInfo check(boolean refresh) {
        if (!enabled) {
            return UpdateInfo.disabled(currentVersion);
        }
        Instant at = checkedAt;
        if (refresh || at == null || Duration.between(at, Instant.now()).compareTo(CACHE_FOR) > 0) {
            fetchLatest();
        }
        Candidate found = latest;
        boolean newer = found != null && compareVersions(found.version(), currentVersion) > 0;
        return new UpdateInfo(true, currentVersion, found == null ? null : found.version(), newer,
                found == null ? null : found.releaseUrl(), found == null ? null : found.notes(), lastError);
    }

    /**
     * Downloads the newest release's installer, checks it, starts it and then quits the app.
     *
     * @throws UpdateFailedException when there is nothing newer, the download fails, or the file does not
     *                               match its published hash
     */
    public void install() {
        if (!enabled) {
            throw new UpdateFailedException("이 실행 방식에서는 자동 업데이트를 쓸 수 없습니다.");
        }
        if (!installing.compareAndSet(false, true)) {
            throw new UpdateFailedException("이미 업데이트를 진행하고 있습니다.");
        }
        try {
            if (latest == null) {
                fetchLatest();
            }
            Candidate found = latest;
            if (found == null || compareVersions(found.version(), currentVersion) <= 0) {
                throw new UpdateFailedException("설치할 새 버전이 없습니다.");
            }
            Path file = download(found);
            try {
                launcher.launch(file);
            } catch (IOException e) {
                throw new UpdateFailedException("설치 프로그램을 실행하지 못했습니다: " + e.getMessage(), e);
            }
            log.info("Started the installer of {}; quitting so it can replace the files", found.version());
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(2000); // lets the answer to the browser go out first
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                quit.run();
            });
        } finally {
            installing.set(false);
        }
    }

    private void fetchLatest() {
        checkedAt = Instant.now();
        try {
            String url = apiBase + "/repos/" + repo + "/releases/latest";
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(API_TIMEOUT)
                            .header("Accept", "application/vnd.github+json")
                            .header("User-Agent", "WINTECH_PDF/" + currentVersion).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                latest = null;
                lastError = null; // nothing published yet is not an error
                return;
            }
            if (response.statusCode() != 200) {
                throw new IOException("GitHub 응답 " + response.statusCode());
            }
            latest = parse(response.body());
            lastError = latest == null ? "릴리스에 설치 파일(WINTECH_PDF_Setup_*.exe)이 없습니다." : null;
        } catch (IOException | RuntimeException e) {
            lastError = "업데이트 정보를 가져오지 못했습니다 (" + e.getClass().getSimpleName() + ")";
            log.info("Update check failed: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastError = "업데이트 확인이 중단되었습니다.";
        }
    }

    /** The installable release in a GitHub "latest release" answer, or null when it carries no installer. */
    Candidate parse(String json) {
        JsonNode release = mapper.readTree(json);
        String tag = release.path("tag_name").asString("");
        JsonNode assets = release.path("assets");
        JsonNode installer = null;
        for (JsonNode asset : assets) {
            if (INSTALLER_NAME.matcher(asset.path("name").asString("")).matches()) {
                installer = asset;
                break;
            }
        }
        if (tag.isBlank() || installer == null) {
            return null;
        }
        String name = installer.path("name").asString("");
        String url = installer.path("browser_download_url").asString("");
        String sha = hashOf(installer.path("digest").asString(""));
        if (sha == null) {
            // Otherwise the release carries "<installer>.sha256" next to the installer.
            for (JsonNode asset : assets) {
                if (asset.path("name").asString("").equalsIgnoreCase(name + ".sha256")) {
                    sha = fetchHash(asset.path("browser_download_url").asString(""));
                    break;
                }
            }
        }
        return new Candidate(tag, tag.replaceFirst("^[vV]", ""), release.path("html_url").asString(""),
                release.path("body").asString(""), name, url, sha);
    }

    private String fetchHash(String url) {
        if (!allowed(url)) {
            return null;
        }
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(API_TIMEOUT)
                            .header("User-Agent", "WINTECH_PDF/" + currentVersion).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? hashOf(response.body()) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** A SHA-256 (64 hex digits) found in {@code text} such as "sha256:ab12..." or "ab12...  file.exe", or null. */
    static String hashOf(String text) {
        Matcher m = SHA256.matcher(text == null ? "" : text);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private boolean allowed(String url) {
        return url != null && url.startsWith(downloadPrefix);
    }

    private Path download(Candidate found) {
        if (!allowed(found.url())) {
            throw new UpdateFailedException("릴리스의 다운로드 주소가 이 프로그램의 저장소가 아닙니다. 설치하지 않았습니다.");
        }
        if (found.sha256() == null) {
            throw new UpdateFailedException("릴리스에 검증용 해시(SHA-256)가 없어 설치하지 않았습니다.");
        }
        try {
            Files.createDirectories(downloadDir);
            Path target = downloadDir.resolve(found.fileName());
            Path partial = downloadDir.resolve(found.fileName() + ".part");
            Files.deleteIfExists(partial);
            HttpResponse<Path> response = http.send(
                    HttpRequest.newBuilder(URI.create(found.url())).timeout(DOWNLOAD_TIMEOUT)
                            .header("User-Agent", "WINTECH_PDF/" + currentVersion).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(partial));
            if (response.statusCode() != 200) {
                Files.deleteIfExists(partial);
                throw new UpdateFailedException("설치 파일을 내려받지 못했습니다 (HTTP " + response.statusCode() + ").");
            }
            if (Files.size(partial) > MAX_INSTALLER_BYTES) {
                Files.deleteIfExists(partial);
                throw new UpdateFailedException("설치 파일이 비정상적으로 큽니다. 설치하지 않았습니다.");
            }
            String actual = sha256(partial);
            if (!actual.equalsIgnoreCase(found.sha256())) {
                Files.deleteIfExists(partial);
                throw new UpdateFailedException("내려받은 파일이 릴리스의 검증값과 다릅니다. 설치하지 않았습니다.");
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UpdateFailedException("설치 파일을 내려받지 못했습니다: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateFailedException("업데이트가 중단되었습니다.", e);
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                for (int read; (read = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Compares dotted numeric versions ("0.051" &lt; "0.100" &lt; "1.0"): positive when {@code a} is newer.
     * Anything that is not a number counts as zero.
     */
    static int compareVersions(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("\\.");
        String[] y = b.replaceFirst("^[vV]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long left = i < x.length ? number(x[i]) : 0;
            long right = i < y.length ? number(y[i]) : 0;
            if (left != right) {
                return Long.compare(left, right);
            }
        }
        return 0;
    }

    private static long number(String part) {
        try {
            return Long.parseLong(part.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Opens the installer the way double-clicking it would, which also brings up Windows' permission prompt. */
    private static void startWithShell(Path installer) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new IOException("Windows에서만 설치할 수 있습니다.");
        }
        new ProcessBuilder("cmd.exe", "/c", "start", "", installer.toString()).start();
    }

    private static void quitApp(ConfigurableApplicationContext context) {
        System.exit(SpringApplication.exit(context));
    }
}
