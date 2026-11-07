package com.pdfedit;

import java.awt.Desktop;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Locale;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.pdfedit.service.UpdateService;

@SpringBootApplication
@EnableScheduling
public class PdfEditApplication {

    private static final int DEFAULT_PORT = 19000;
    private static final int MAX_PORT_ATTEMPTS = 20;

    public static void main(String[] args) {
        boolean installed = InstalledApp.isInstalled();
        if (!installed) {
            start(args, false);
            return;
        }
        try {
            String version = InstalledApp.ownVersion();
            // Double-clicking the shortcut again should bring up the running copy, not start another.
            if (!InstalledApp.claimSingleInstance()) {
                InstalledApp.Running running = InstalledApp.awaitRunningInstance(60_000);
                boolean older = running != null && version != null
                        && (running.version() == null || UpdateService.compareVersions(running.version(), version) < 0);
                if (older && InstalledApp.takeOver()) {
                    // An older version was still running: this one starts in its place.
                } else if (running != null) {
                    openBrowser("http://localhost:" + running.port());
                    return;
                } else {
                    InstalledApp.showStartFailure("이미 실행 중인 WINTECH 문서편집기가 1분 동안 응답하지 않습니다.\n"
                            + "작업 관리자에서 'WINTECH DocEditor'를 끝낸 뒤 다시 실행해 주세요.");
                    System.exit(1);
                    return;
                }
            }
            start(args, true);
        } catch (Throwable e) {
            // The cause is in the log (Spring Boot writes it there as well).
            org.slf4j.LoggerFactory.getLogger(PdfEditApplication.class).error("The app could not start", e);
            InstalledApp.showStartFailure("원인: " + cause(e));
            System.exit(1);
        }
    }

    /** The innermost reason, as one short line. */
    private static String cause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static void start(String[] args, boolean installed) {
        int port = findAvailablePort(resolvePreferredPort());
        System.setProperty("server.port", String.valueOf(port));
        if (installed) {
            InstalledApp.publishPort(port, InstalledApp.ownVersion());
        }
        // Spring Boot forces java.awt.headless=true by default, which turns Desktop.getDesktop()
        // into a no-op, so it is switched off - but only where a desktop really exists. On a
        // display-less Linux server/container, headless=false makes Java AWT load its X11 library
        // (missing there) and every page render then fails with NoClassDefFoundError.
        boolean desktop = hasDesktop();
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(PdfEditApplication.class).headless(!desktop).run(args);
        String url = "http://localhost:" + port;
        AotTraining.refreshIfStale(context.getEnvironment().getProperty("app.version", ""));
        if (AotTraining.requested()) {
            // The installer build's training run: use the app once as the screen would, then quit.
            AotTraining.exercise(url);
            context.close();
            System.exit(0);
        }
        if (desktop && !"true".equalsIgnoreCase(System.getenv("PDFEDIT_NO_BROWSER"))) {
            openBrowser(url);
        }
        if (desktop && installed) {
            // The installed app has no window, so the tray icon is how it is reopened and quit.
            InstalledApp.addTrayIcon(context, url, context.getEnvironment().getProperty("app.version", ""),
                    () -> openBrowser(url));
        }
    }

    private static boolean hasDesktop() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win") || os.contains("mac")) {
            return true;
        }
        return System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
    }

    /** SERVER_PORT wins if set; otherwise 19000. */
    private static int resolvePreferredPort() {
        String fromEnv = System.getenv("SERVER_PORT");
        if (fromEnv != null) {
            try {
                return Integer.parseInt(fromEnv.trim());
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return DEFAULT_PORT;
    }

    /**
     * Tries the preferred port first, then walks upward until one is actually free. The app runs
     * standalone on each user's own PC, so the preferred port may already be taken; the native
     * launcher has no console to show Spring Boot's own "port already in use" error on. Binding a
     * throwaway ServerSocket here - before Spring Boot tries - means Tomcat's own bind succeeds.
     */
    private static int findAvailablePort(int preferred) {
        for (int i = 0; i < MAX_PORT_ATTEMPTS; i++) {
            int candidate = preferred + i;
            try (ServerSocket socket = new ServerSocket(candidate)) {
                return candidate;
            } catch (IOException portTaken) {
                // try the next one
            }
        }
        return preferred;
    }

    /** Opens the UI only when a desktop is available; headless servers skip it. */
    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception | LinkageError ignored) {
            // Best-effort only - the server is still up even if the browser didn't open.
        }
    }
}
