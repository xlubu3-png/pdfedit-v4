package com.pdfedit.controller;

import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.pdfedit.ocr.OcrUnavailableException;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PageNotEditableException;
import com.pdfedit.service.PasswordRequiredException;
import com.pdfedit.service.UpdateFailedException;
import com.pdfedit.text.FontUnavailableException;

@RestControllerAdvice
public class ApiExceptionHandler {

    /** spring.servlet.multipart.max-file-size, in MB (the screen checks the same size before sending). */
    static final int MAX_UPLOAD_MB = 100;

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleUnknownDocument(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    @ExceptionHandler(IndexOutOfBoundsException.class)
    public ResponseEntity<String> handleUnknownPage(IndexOutOfBoundsException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("없는 페이지입니다.");
    }

    /** A file over spring.servlet.multipart.max-file-size: said in Korean, not as the web server's English page. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> handleTooBig(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body("파일이 너무 큽니다. 한 파일에 " + MAX_UPLOAD_MB + "MB까지 열 수 있습니다.");
    }

    @ExceptionHandler(FontUnavailableException.class)
    public ResponseEntity<String> handleNoFont(FontUnavailableException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(e.getMessage());
    }

    @ExceptionHandler(InvalidEditException.class)
    public ResponseEntity<String> handleInvalidEdit(InvalidEditException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    /** 401 tells the page to ask for the password and send the file again. */
    @ExceptionHandler(PasswordRequiredException.class)
    public ResponseEntity<String> handlePasswordRequired(PasswordRequiredException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
    }

    @ExceptionHandler(UpdateFailedException.class)
    public ResponseEntity<String> handleUpdateFailed(UpdateFailedException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(e.getMessage());
    }

    @ExceptionHandler(OcrUnavailableException.class)
    public ResponseEntity<String> handleNoOcr(OcrUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
    }

    @ExceptionHandler(PageNotEditableException.class)
    public ResponseEntity<String> handleNotEditable(PageNotEditableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
