package com.pdfedit;

import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

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

    /** Port of a copy of this app that is already serving, or -1. */
    static int findRunningInstance(int firstPort, int attempts) {
        for (int port = firstPort; port < firstPort + attempts; port++) {
            try {
                HttpURLConnection connection = (HttpURLConnection) URI
                        .create("http://localhost:" + port + "/api/v1/pdf/info").toURL().openConnection();
                connection.setConnectTimeout(300);
                connection.setReadTimeout(1500);
                if (connection.getResponseCode() == 200) {
                    try (InputStream body = connection.getInputStream()) {
                        String text = new String(body.readNBytes(512), StandardCharsets.UTF_8);
                        if (text.contains("installedFonts")) {
                            return port;
                        }
                    }
                }
            } catch (IOException notOurs) {
                // nothing listening here, or something else: keep looking
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
