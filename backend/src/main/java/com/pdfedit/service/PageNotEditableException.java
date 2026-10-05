package com.pdfedit.service;

/** The page's text cannot be edited (e.g. the page carries a /Rotate entry). */
public class PageNotEditableException extends RuntimeException {

    public PageNotEditableException(String message) {
        super(message);
    }
}
