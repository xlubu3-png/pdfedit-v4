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
 * and quit it. The installer's launcher sets {@code jpackage.app-path}; during development and in
 * Docker it is absent, so none of this runs there.
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

    /** Tells later launches which port this copy serves on. */
    static void publishPort(int port) {
        try {
            Files.writeString(PORT_FILE, String.valueOf(port));
        } catch (IOException ignored) {
            // A second launch then just can't find the port and gives up waiting.
        }
    }

    /**
     * Port of the copy that holds the claim, waiting for it to finish starting; -1 if it did not come
     * up in time. The browser only gets pointed at a port that accepts connections.
     */
    static int awaitRunningInstance(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                int port = Integer.parseInt(Files.readString(PORT_FILE).trim());
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("localhost", port), 300);
                    return port;
                }
            } catch (IOException | NumberFormatException notYet) {
                // port not published yet, or the server is not accepting yet
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return -1;
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

            TrayIcon icon = new TrayIcon(image, "WINTECH_PDF v" + version + " (" + url + ")", menu);
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
