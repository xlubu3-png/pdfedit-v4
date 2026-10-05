package com.pdfedit.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * An optional password for the whole app, for installations other people can reach (a NAS, a shared
 * server). When {@code app.password} (env {@code PDFEDIT_PASSWORD}) is set, every request must carry it
 * as HTTP Basic credentials - the browser asks once and remembers - with any user name. The health check
 * stays open. With no password set, this filter does nothing.
 */
@Component
public class AccessPasswordFilter extends OncePerRequestFilter {

    private final byte[] password;

    public AccessPasswordFilter(@Value("${app.password:}") String password) {
        this.password = password.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (password.length == 0 || request.getRequestURI().equals("/api/v1/pdf/health") || accepted(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("WWW-Authenticate", "Basic realm=\"WINTECH_PDF\", charset=\"UTF-8\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private boolean accepted(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon >= 0 && MessageDigest.isEqual(decoded.substring(colon + 1).getBytes(StandardCharsets.UTF_8),
                    password);
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
    }
}
