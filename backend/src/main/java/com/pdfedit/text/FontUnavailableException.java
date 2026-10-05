package com.pdfedit.text;

/** No installed font can draw the edited text. The message is shown to the user as is. */
public class FontUnavailableException extends RuntimeException {

    public FontUnavailableException(String message) {
        super(message);
    }

    public FontUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
