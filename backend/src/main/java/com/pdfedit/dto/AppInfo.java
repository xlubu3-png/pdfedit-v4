package com.pdfedit.dto;

/**
 * What the UI shows about the running app.
 *
 * @param version        the app version, bumped by 0.001 with every change
 * @param installedFonts how many fonts were read from this computer's font folders
 */
public record AppInfo(String version, int installedFonts) {
}
