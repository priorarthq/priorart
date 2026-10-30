package io.priorart.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Rejects oversized bodies before they are parsed: transcripts are compacted to ≤15 MB. */
@Component
@Order(1)
public class RequestLimits extends OncePerRequestFilter {
    static final long MAX_BODY = 20L * 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long len = req.getContentLengthLong();
        long max = MAX_BODY;
        if (len > max) {
            res.setStatus(413);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"request body too large (max " + (max / 1024) + " KB)\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
