package com.verifi.security;

import com.verifi.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;

public final class Auth {
    public static final String ATTR = "verifi.user";

    private Auth() {}

    public static AuthUser require(HttpServletRequest request) {
        Object value = request.getAttribute(ATTR);
        if (value instanceof AuthUser user) return user;
        throw new ApiException(401, "Unauthorized", "UNAUTHORIZED");
    }

    public static AuthUser requireAdmin(HttpServletRequest request) {
        AuthUser user = require(request);
        if (!"admin".equals(user.role())) {
            throw new ApiException(403, "Admin access required", "FORBIDDEN");
        }
        return user;
    }

    public static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
