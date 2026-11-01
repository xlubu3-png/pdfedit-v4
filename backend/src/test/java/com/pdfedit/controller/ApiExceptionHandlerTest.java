package com.pdfedit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.pdfedit.service.PdfDocumentStore;

/** What the screen is told when a request fails is in Korean, like the rest of the app. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void aFileOverTheSizeLimitIsRefusedInKorean() {
        var response = handler.handleTooBig(new MaxUploadSizeExceededException(100L * 1024 * 1024));

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).isEqualTo("파일이 너무 큽니다. 한 파일에 100MB까지 열 수 있습니다.");
    }

    @Test
    void anUnknownPageIsSaidInKorean() {
        var response = handler.handleUnknownPage(new IndexOutOfBoundsException("Index 5 out of bounds for length 1"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isEqualTo("없는 페이지입니다.");
    }

    @Test
    void aDocumentTheAppNoLongerHoldsIsSaidInKorean() {
        PdfDocumentStore store = new PdfDocumentStore();

        assertThatThrownBy(() -> store.getInfo("0b9f6c1e-0000-0000-0000-000000000000"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("문서를 찾을 수 없습니다. 보관 기간이 지나 지워졌을 수 있으니 파일을 다시 열어 주세요.");
        var response = handler.handleUnknownDocument(new NoSuchElementException("문서를 찾을 수 없습니다."));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isEqualTo("문서를 찾을 수 없습니다.");
    }
}
