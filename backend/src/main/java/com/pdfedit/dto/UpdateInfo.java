package com.pdfedit.dto;

/**
 * Whether a newer version of the app is available, as the UI shows it.
 *
 * @param enabled    updating is switched on (only the Windows installer build; not in development or Docker)
 * @param current    the version that is running
 * @param latest     the newest published version, or null when it could not be found out
 * @param newer      {@code latest} is newer than {@code current} and can be installed
 * @param releaseUrl the web page of the newest release, with what changed
 * @param notes      the release's description, as written when it was published
 * @param error      why the check failed (offline, GitHub unreachable, ...), or null
 */
public record UpdateInfo(boolean enabled, String current, String latest, boolean newer, String releaseUrl,
                         String notes, String error) {

    public static UpdateInfo disabled(String current) {
        return new UpdateInfo(false, current, null, false, null, null, null);
    }
}
