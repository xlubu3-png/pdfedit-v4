package com.pdfedit.dto;

/**
 * What the user can set in the app.
 *
 * @param retentionDays   how many days an uploaded document nobody opens is kept before it is cleaned up
 * @param straightenOnOcr whether recognizing a scan's text first sets its pages straight (fed askew, on their side
 *                        or upside down); null when not given (left as it is)
 */
public record AppSettings(int retentionDays, Boolean straightenOnOcr) {

    public AppSettings(int retentionDays) {
        this(retentionDays, null);
    }
}
