package com.pdfedit.ocr;

/** The text recognition engine cannot run here (its native library or models failed to load). */
public class OcrUnavailableException extends RuntimeException {

    public OcrUnavailableException(Throwable cause) {
        super("글자 인식(OCR) 엔진을 시작하지 못했습니다. 앱을 다시 설치하거나 관리자에게 문의해 주세요.", cause);
    }
}
