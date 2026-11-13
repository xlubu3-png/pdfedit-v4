package com.pdfedit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.management.HotSpotDiagnosticMXBean;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java's AOT cache (JEP 483/514/515): what the JVM loaded, linked and learned while the app started and opened a
 * document, kept in a file so later starts skip that work - the app starts about twice as fast.
 * <p>
 * The installed launcher points {@code -XX:AOTCache} at {@code ~/WINTECH_PDF/app.aot}. The cache must be made with
 * the very program files it is used with (Java checks their size and time, and installing changes the time), so it
 * is made here, on the user's PC: when a start finds it missing or stale (first start, after an update), the app runs
 * itself once more in the background - a training run ({@link #PROPERTY}) that opens a small document the way the
 * screen does and quits - and the next start uses the cache. Until then, and whenever anything fails, the app simply
 * starts the ordinary way.
 */
final class AotTraining {

    static final String PROPERTY = "app.aot-training";
    private static final Logger log = LoggerFactory.getLogger(AotTraining.class);
    /** The training run waits this long after the app started, not to slow the user's first steps. */
    private static final long DELAY_MS = 20_000;
    /** A training run taking longer than this is stuck: it is stopped. */
    private static final long TIMEOUT_MS = 180_000;
    /** The training run waits for this long without work on documents... */
    private static final long QUIET_MS = 30_000;
    /** ...but no longer than this. */
    private static final long QUIET_WAIT_MAX_MS = 15 * 60_000;

    private AotTraining() {
    }

    static boolean requested() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** The cache file the launcher gave, or null when none was given. */
    static Path cacheFile() {
        try {
            var vm = ManagementFactory.getPlatformMXBean(
                    HotSpotDiagnosticMXBean.class);
            String value = vm.getVMOption("AOTCache").getValue();
            return value.isEmpty() ? null : Path.of(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether classes came from the cache this start ("mixed mode, sharing"; without "sharing" it was refused). */
    static boolean cacheUsed() {
        return System.getProperty("java.vm.info", "").contains("sharing");
    }

    /**
     * Logs how this start used the cache and, when one was given but not used, makes it in the background for the
     * next start. Once per app version: should the cache made still not be taken (or the run fail), the app goes on
     * starting the ordinary way rather than training at every start.
     */
    static void refreshIfStale(String version) {
        Path cache = cacheFile();
        if (cache == null || requested()) {
            return;
        }
        if (cacheUsed()) {
            log.info("AOT cache used: {}", cache);
            return;
        }
        Path tried = cache.resolveSibling(cache.getFileName() + ".version");
        try {
            if (Files.exists(tried) && Files.readString(tried).strip().equals(version)) {
                log.info("AOT cache not used; it was already made once for {}", version);
                return;
            }
        } catch (IOException e) {
            // try it
        }
        log.info("AOT cache not used ({}): it is made in the background for the next start", cache);
        Thread.ofPlatform().daemon().name("aot-training").priority(Thread.MIN_PRIORITY).start(() -> {
            try {
                Thread.sleep(DELAY_MS);
                // Not while the user is at work (opening and reading scans right after an update): the training
                // run takes the CPU for some 20 seconds. After a long busy spell it runs anyway.
                long waitedUntil = System.nanoTime() + QUIET_WAIT_MAX_MS * 1_000_000;
                while (!Activity.quietFor(QUIET_MS) && System.nanoTime() < waitedUntil) {
                    Thread.sleep(2_000);
                }
                Files.createDirectories(cache.toAbsolutePath().getParent());
                if (train(cache) != null) {
                    // Finished, made or not (not when the app quit meanwhile): not tried again for this version.
                    Files.writeString(tried, version);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                log.warn("AOT cache training failed", e);
            }
        });
    }

    /**
     * Runs the training run, with this JVM's runtime, files and options: whether the cache was made, or null when the
     * run was stopped (the app quitting) or could not start.
     */
    private static Boolean train(Path cache) throws IOException, InterruptedException {
        Path launcher = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
        if (!Files.isExecutable(launcher)) {
            log.info("AOT cache: no java launcher in {}", launcher.getParent());
            return Boolean.FALSE;
        }
        Path made = cache.resolveSibling(cache.getFileName() + ".new");
        Path data = Files.createTempDirectory("wintech-aot-");
        List<String> command = new ArrayList<>();
        command.add(launcher.toString());
        for (String option : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            // The same JVM settings (the cache is refused by a JVM set up differently), but not the installed app's
            // identity: the training run is not the running app (no single-instance lock, no log file of its own).
            if (option.startsWith("-XX:AOTCache") || option.startsWith("-Djpackage.")
                    || option.startsWith("-Dspring.profiles.active") || option.startsWith("-Xlog")
                    || option.startsWith("-Djava.class.path")) {
                continue;
            }
            command.add(option);
        }
        command.add("-XX:AOTCacheOutput=" + made);
        command.add("-D" + PROPERTY + "=true");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(PdfEditApplication.class.getName());
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("PDFEDIT_NO_BROWSER", "true");
        builder.environment().put("PDFEDIT_DATA_DIR", data.toString());
        try (ServerSocket free = new ServerSocket(0)) {
            builder.environment().put("SERVER_PORT", String.valueOf(free.getLocalPort()));
        }
        long started = System.nanoTime();
        Process process = builder.start();
        // Quitting the app (to update it, say) stops the training run too: it holds the program's files open,
        // which the installer would have to wait for.
        // (With the JVM it starts at its end to write the cache.)
        AtomicBoolean stopped = new AtomicBoolean();
        Thread stop = new Thread(() -> {
            stopped.set(true);
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }, "aot-training-stop");
        Runtime.getRuntime().addShutdownHook(stop);
        try {
            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                log.warn("AOT cache training run took too long; stopped");
                return Boolean.FALSE;
            }
            if (stopped.get()) {
                return null;
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(made)) {
                log.warn("AOT cache training run failed (exit {})", process.exitValue());
                return Boolean.FALSE;
            }
            Files.move(made, cache, StandardCopyOption.REPLACE_EXISTING);
            log.info("AOT cache made in {} s: {}", (System.nanoTime() - started) / 1_000_000_000, cache);
            return Boolean.TRUE;
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(stop);
            } catch (IllegalStateException shuttingDown) {
                // the hook is running
            }
            Files.deleteIfExists(made);
            // The JVM's notes from the training, written next to the cache it makes.
            Files.deleteIfExists(made.resolveSibling(made.getFileName() + ".config"));
            deleteTree(data);
        }
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // a temporary folder left behind
        }
    }

    /** Uses the running app as the screen would; failures are only reported (the cache is still made). */
    static void exercise(String base) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        try {
            get(http, base + "/");
            get(http, base + "/api/v1/pdf/health");
            String boundary = "----aot" + UUID.randomUUID();
            byte[] body = multipart(boundary, samplePdf());
            HttpResponse<String> upload = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/pdf/documents"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
            Matcher id = Pattern.compile("\"documentId\"\\s*:\\s*\"([^\"]+)\"").matcher(upload.body());
            if (!id.find()) {
                System.err.println("AOT training: upload failed: " + upload.statusCode());
                return;
            }
            String doc = base + "/api/v1/pdf/documents/" + id.group(1);
            get(http, doc);
            get(http, doc + "/pages/0/thumbnail?width=240");
            get(http, doc + "/pages/0/text-runs");
            get(http, doc + "/pages/0/rulings");
            get(http, doc + "/pages/0/preview?dpi=96");
            http.send(HttpRequest.newBuilder(URI.create(doc)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            System.err.println("AOT training: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void get(HttpClient http, String url) throws IOException, InterruptedException {
        http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding());
    }

    /** A page with a small table: lines of text and the lines around them. */
    private static byte[] samplePdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 760);
                cs.showText("Quotation No. 2026-001");
                cs.endText();
                for (int row = 0; row <= 3; row++) {
                    cs.moveTo(72, 700 - row * 24);
                    cs.lineTo(472, 700 - row * 24);
                }
                for (int col = 0; col <= 2; col++) {
                    cs.moveTo(72 + col * 200, 700);
                    cs.lineTo(72 + col * 200, 628);
                }
                cs.stroke();
                for (int row = 0; row < 3; row++) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    cs.newLineAtOffset(78, 683 - row * 24);
                    cs.showText("Item " + (row + 1));
                    cs.newLineAtOffset(200, 0);
                    cs.showText(String.valueOf((row + 1) * 1200));
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] multipart(String boundary, byte[] pdf) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(pdf);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
