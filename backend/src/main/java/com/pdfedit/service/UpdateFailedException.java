package com.pdfedit.service;

/** The update could not be fetched or did not pass the checks; answered with HTTP 502 and a readable reason. */
public class UpdateFailedException extends RuntimeException {

    public UpdateFailedException(String message) {
        super(message);
    }

    public UpdateFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
