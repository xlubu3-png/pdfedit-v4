package com.pdfedit.dto;

/**
 * What the user can set in the app.
 *
 * @param retentionDays   how many days an uploaded document nobody opens is kept before it is cleaned up
 * @param straightenOnOcr whether recognizing a scan's text first sets its pages straight (fed askew, on their side
 *                        or upside down); null when not given (left as it is)
 * @param autoOcr         whether a scanned document is recognized as soon as it is opened, without asking; null when
 *                        not given (left as it is)
 */
public record AppSettings(int retentionDays, Boolean straightenOnOcr, Boolean autoOcr) {

    public AppSettings(int retentionDays) {
        this(retentionDays, null, null);
    }

    public AppSettings(int retentionDays, Boolean straightenOnOcr) {
        this(retentionDays, straightenOnOcr, null);
    }
}
