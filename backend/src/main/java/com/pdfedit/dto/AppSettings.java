package com.pdfedit.dto;

/**
 * What the user can set in the app.
 *
 * @param retentionDays how many days an uploaded document nobody opens is kept before it is cleaned up
 */
public record AppSettings(int retentionDays) {
}
