package com.pdfedit.service;

/** An uploaded PDF is encrypted and the password that came with it was missing or wrong. */
public class PasswordRequiredException extends RuntimeException {

    public PasswordRequiredException(String message) {
        super(message);
    }
}
