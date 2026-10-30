package io.priorart.auth;

import io.priorart.domain.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Optional;

/** Team API calls need a personal token (Authorization: Bearer pt_…), except claiming a handle. */
@Component
@Order(2)
public class TeamAuthFilter extends OncePerRequestFilter {
    private final Identity identity;

    public TeamAuthFilter(Identity identity) { this.identity = identity; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        boolean open = !identity.required() || !path.startsWith("/api/")
                || ("POST".equals(req.getMethod()) && path.equals("/api/users/claim"));
        if (open) { chain.doFilter(req, res); return; }
        String auth = req.getHeader("Authorization");
        Optional<User> u = identity.byToken(auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : null);
        if (u.isEmpty()) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"personal token required: claim your handle with priorart.mjs config --handle <name>\"}");
            return;
        }
        req.setAttribute(Identity.ATTR, u.get());
        chain.doFilter(req, res);
    }
}
