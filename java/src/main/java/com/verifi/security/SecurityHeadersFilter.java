package com.verifi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifi.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SecurityHeadersFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        int length = request.getContentLength();
        if (length > 10 * 1024) {
            write(response, 413, "Payload too large", "PAYLOAD_TOO_LARGE");
            return;
        }
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        filterChain.doFilter(request, response);
    }

    static void write(HttpServletResponse response, int status, String error, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("code", code);
        new ObjectMapper().writeValue(response.getWriter(), body);
    }
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimiter limiter;
    private final AppProperties props;

    RateLimitFilter(RateLimiter limiter, AppProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        String ip = Auth.clientIp(request);
        if (path.startsWith("/api") && !limiter.allow("api:" + ip, props.getApiRateLimitMax())) {
            SecurityHeadersFilter.write(response, 429, "Too many requests, please try again later.", "RATE_LIMITED");
            return;
        }
        if (path.startsWith("/api/coach") && !limiter.allow("coach:" + ip, props.getCoachRateLimitMax())) {
            SecurityHeadersFilter.write(response, 429, "Too many coach requests. Please try again later.", "RATE_LIMITED");
            return;
        }
        filterChain.doFilter(request, response);
    }
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
class AuthFilter extends OncePerRequestFilter {
    private final JwtService jwt;

    AuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || !request.getRequestURI().startsWith("/api") || isPublic(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
        if (token == null || token.isEmpty()) {
            SecurityHeadersFilter.write(response, 401, "Authorization required", "UNAUTHORIZED");
            return;
        }
        try {
            request.setAttribute(Auth.ATTR, jwt.verify(token));
        } catch (Exception ex) {
            SecurityHeadersFilter.write(response, 401, "Invalid or expired token", "TOKEN_INVALID");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return ("POST".equals(method) && ("/api/auth/nonce".equals(path) || "/api/auth/verify".equals(path) || "/api/auth/demo".equals(path) || "/api/categorize".equals(path)))
                || ("GET".equals(method) && "/api/categorize/categories".equals(path));
    }
}
