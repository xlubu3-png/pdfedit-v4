package com.pdfedit;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Whether the user is at work: the screen's requests on documents going on now, and when the last one ended. Work
 * the app does for itself in the background (making the AOT cache after an update) waits for a quiet moment, not to
 * slow what the user is doing.
 */
@Component
public class Activity extends OncePerRequestFilter {

    private static final AtomicInteger RUNNING = new AtomicInteger();
    private static volatile long lastEnded = System.nanoTime();

    /** Whether no work on documents has gone on for at least this long. */
    public static boolean quietFor(long millis) {
        return RUNNING.get() == 0 && (System.nanoTime() - lastEnded) / 1_000_000 >= millis;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only work on documents counts: the page's own files and the health check are no work.
        return !request.getRequestURI().startsWith("/api/v1/pdf/documents");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RUNNING.incrementAndGet();
        try {
            chain.doFilter(request, response);
        } finally {
            lastEnded = System.nanoTime();
            RUNNING.decrementAndGet();
        }
    }
}
