package com.pdfedit.dto;

import java.util.List;

/**
 * What the UI shows about the running app.
 *
 * @param version          the app version, bumped by 0.001 with every change
 * @param installedFonts   how many fonts were read from this computer's font folders
 * @param unreadableFonts  families installed as .otf files (CFF outlines) that the app cannot use, so the screen can
 *                         say why a font the user installed is not picked up
 */
public record AppInfo(String version, int installedFonts, List<String> unreadableFonts) {

    public AppInfo(String version, int installedFonts) {
        this(version, installedFonts, List.of());
    }
}
