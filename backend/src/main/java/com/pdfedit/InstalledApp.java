package com.pdfedit;

import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import javax.imageio.ImageIO;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * What only makes sense for the Windows installer build, where the app is a background process the
 * user starts from a shortcut: finding a copy that is already running, and a tray icon to reopen
 * and quit it. The installer's launcher sets {@code jpackage.app-path}; during development it is
 * absent, so none of this runs there.
 */
final class InstalledApp {

    private InstalledApp() {
    }

    static boolean isInstalled() {
        return System.getProperty("jpackage.app-path") != null;
    }

    private static final Path DIR = Path.of(System.getProperty("user.home"), "WINTECH_PDF");
    private static final Path LOCK_FILE = DIR.resolve("instance.lock");
    private static final Path PORT_FILE = DIR.resolve("instance.port");

    /** Kept open for the life of the process: the operating system drops the lock when it exits, even by crash. */
    @SuppressWarnings("unused")
    private static FileChannel heldLock;

    /**
     * Claims "this is the running copy". Returns true if this process now is it; false if another copy
     * already holds the claim. A file lock is used instead of asking the other copy over HTTP because
     * that cannot see a copy that is still starting up or busy reading the font folders.
     * If the lock cannot be used at all, this says true so the app still starts.
     */
    static boolean claimSingleInstance() {
        try {
            Files.createDirectories(DIR);
            FileChannel channel = FileChannel.open(LOCK_FILE, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return false;
            }
            heldLock = channel;
            // A port left behind by an earlier run must not be mistaken for this run's.
            Files.deleteIfExists(PORT_FILE);
            return true;
        } catch (IOException | OverlappingFileLockException e) {
            return !(e instanceof OverlappingFileLockException);
        }
    }

    /** The copy that is running: its port, and its version (null for versions before 0.130, which wrote none). */
    record Running(int port, String version) {
    }

    /** This program's version, as application.yml has it ("0.130"); null if it cannot be read. */
    static String ownVersion() {
        try (var in = InstalledApp.class.getResourceAsStream("/application.yml")) {
            if (in == null) {
                return null;
            }
            var m = java.util.regex.Pattern.compile("(?m)^\\s*version:\\s*\"?([0-9.]+)")
                    .matcher(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Tells later launches which port this copy serves on, and which version it is. */
    static void publishPort(int port, String version) {
        try {
            Files.writeString(PORT_FILE, port + (version == null ? "" : "\n" + version));
        } catch (IOException ignored) {
            // A second launch then just can't find the port and gives up waiting.
        }
    }

    /**
     * Port of the copy that holds the claim, waiting for it to finish starting; -1 if it did not come
     * up in time. The browser only gets pointed at a port that accepts connections.
     */
    static Running awaitRunningInstance(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                String[] lines = Files.readString(PORT_FILE).strip().split("\\R");
                int port = Integer.parseInt(lines[0].trim());
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
                    return new Running(port, lines.length > 1 ? lines[1].trim() : null);
                }
            } catch (IOException | NumberFormatException notYet) {
                // port not published yet, or the server is not accepting yet
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * Ends an older version still running from this same program (the update installed over it, but it had not
     * quit, or was started from a shortcut before), so this newer one starts in its place instead of handing over
     * to it; true when this copy then holds the claim. Its work is saved as it goes (the documents and edits are on
     * disk), and the page in the browser carries on with this one.
     */
    static boolean takeOver() {
        String self = ProcessHandle.current().info().command().orElse(null);
        if (self == null) {
            return false;
        }
        long me = ProcessHandle.current().pid();
        java.util.List<ProcessHandle> old = ProcessHandle.allProcesses()
                .filter(p -> p.pid() != me && p.info().command().map(self::equalsIgnoreCase).orElse(false))
                .toList();
        if (old.isEmpty()) {
            return false;
        }
        old.forEach(ProcessHandle::destroy);
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (claimSingleInstance()) {
                return true;
            }
            if (old.stream().noneMatch(ProcessHandle::isAlive)) {
                // Gone, but its lock is released a moment later.
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                continue;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (System.currentTimeMillis() > deadline - 10_000) {
                old.forEach(ProcessHandle::destroyForcibly);
            }
        }
        return claimSingleInstance();
    }

    /**
     * Tells the person at the PC that the program could not start, and why, in a window: the installed program has
     * no console, so otherwise nothing at all would happen when they open it.
     */
    static void showStartFailure(String message) {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                return;
            }
            String log = DIR.resolve("app.log").toString();
            javax.swing.JOptionPane.showMessageDialog(null,
                    "WINTECH 문서편집기를 시작하지 못했습니다.\n\n" + message
                            + "\n\n다시 실행해 보고, 계속 안 되면 PC를 다시 시작하거나 프로그램을 다시 설치해 주세요.\n"
                            + "자세한 기록: " + log,
                    "WINTECH 문서편집기", javax.swing.JOptionPane.ERROR_MESSAGE);
        } catch (RuntimeException | LinkageError noWindow) {
            // nothing more can be shown
        }
    }

    static void addTrayIcon(ConfigurableApplicationContext context, String url, String version, Runnable open) {
        try {
            if (!SystemTray.isSupported()) {
                return;
            }
            Image image = ImageIO.read(InstalledApp.class.getResource("/tray-icon.png"));
            PopupMenu menu = new PopupMenu();
            MenuItem openItem = new MenuItem("열기");
            openItem.addActionListener(e -> open.run());
            MenuItem exitItem = new MenuItem("종료");
            menu.add(openItem);
            menu.addSeparator();
            menu.add(exitItem);

            TrayIcon icon = new TrayIcon(image, "WINTECH 문서편집기 v" + version + " (" + url + ")", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> open.run());
            exitItem.addActionListener(e -> {
                SystemTray.getSystemTray().remove(icon);
                System.exit(SpringApplication.exit(context));
            });
            SystemTray.getSystemTray().add(icon);
        } catch (Exception | LinkageError trayUnavailable) {
            // Without a tray the app still works; it just can't be quit from the tray.
        }
    }
}
