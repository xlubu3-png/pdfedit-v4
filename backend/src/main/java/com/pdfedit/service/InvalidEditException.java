package com.pdfedit.service;

/** An edit the server cannot apply as sent (nonsense size, colour or position); answered with HTTP 400. */
public class InvalidEditException extends RuntimeException {

    public InvalidEditException(String message) {
        super(message);
    }
}
