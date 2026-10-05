package com.pdfedit.controller;

import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PageNotEditableException;
import com.pdfedit.service.UpdateFailedException;
import com.pdfedit.text.FontUnavailableException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleUnknownDocument(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    @ExceptionHandler(IndexOutOfBoundsException.class)
    public ResponseEntity<String> handleUnknownPage(IndexOutOfBoundsException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Unknown page: " + e.getMessage());
    }

    @ExceptionHandler(FontUnavailableException.class)
    public ResponseEntity<String> handleNoFont(FontUnavailableException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(e.getMessage());
    }

    @ExceptionHandler(InvalidEditException.class)
    public ResponseEntity<String> handleInvalidEdit(InvalidEditException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    @ExceptionHandler(UpdateFailedException.class)
    public ResponseEntity<String> handleUpdateFailed(UpdateFailedException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(e.getMessage());
    }

    @ExceptionHandler(PageNotEditableException.class)
    public ResponseEntity<String> handleNotEditable(PageNotEditableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
