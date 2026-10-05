package com.pdfedit.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;

class AccessPasswordFilterTest {

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletResponse call(AccessPasswordFilter filter, String uri, String authorization)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        // a request that got through reaches the chain; one that did not has an error status
        if (chain.getRequest() != null) {
            response.setStatus(200);
        }
        return response;
    }

    @Test
    void withoutAPasswordEverythingIsOpen() throws Exception {
        assertThat(call(new AccessPasswordFilter(""), "/api/v1/pdf/info", null).getStatus()).isEqualTo(200);
    }

    @Test
    void withAPasswordARequestWithoutItIsAskedToLogIn() throws Exception {
        MockHttpServletResponse response = call(new AccessPasswordFilter("s3cret"), "/api/v1/pdf/info", null);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Basic");
    }

    @Test
    void aWrongPasswordIsRefusedAndTheRightOneAcceptedWithAnyUserName() throws Exception {
        AccessPasswordFilter filter = new AccessPasswordFilter("s3cret");

        assertThat(call(filter, "/api/v1/pdf/info", basic("me", "wrong")).getStatus()).isEqualTo(401);
        assertThat(call(filter, "/api/v1/pdf/info", basic("me", "s3cret")).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/api/v1/pdf/info", basic("", "s3cret")).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/api/v1/pdf/info", basic("me", "s3cret-and-more")).getStatus()).isEqualTo(401);
    }

    @Test
    void garbageCredentialsAreRefusedNotACrash() throws Exception {
        AccessPasswordFilter filter = new AccessPasswordFilter("s3cret");

        assertThat(call(filter, "/x", "Basic !!!not-base64!!!").getStatus()).isEqualTo(401);
        assertThat(call(filter, "/x", "Bearer abc").getStatus()).isEqualTo(401);
        assertThat(call(filter, "/x", "Basic " + Base64.getEncoder().encodeToString("nocolon".getBytes())).getStatus())
                .isEqualTo(401);
    }

    @Test
    void theHealthCheckStaysOpen() throws Exception {
        assertThat(call(new AccessPasswordFilter("s3cret"), "/api/v1/pdf/health", null).getStatus()).isEqualTo(200);
    }
}
