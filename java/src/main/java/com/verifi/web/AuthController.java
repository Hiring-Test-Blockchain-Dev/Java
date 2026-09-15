package com.verifi.web;

import com.verifi.config.AppProperties;
import com.verifi.error.ApiException;
import com.verifi.security.Auth;
import com.verifi.security.RateLimiter;
import com.verifi.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final RateLimiter limiter;
    private final AppProperties props;

    public AuthController(AuthService auth, RateLimiter limiter, AppProperties props) {
        this.auth = auth;
        this.limiter = limiter;
        this.props = props;
    }

    @PostMapping("/nonce")
    public Map<String, Object> nonce(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String ip = Auth.clientIp(request);
        if (!limiter.allow("nonce:" + ip, props.getNonceRateLimitMax())) {
            throw new ApiException(429, "Too many nonce requests. Try again shortly.", "RATE_LIMITED");
        }
        return auth.nonce(body == null ? null : String.valueOf(body.getOrDefault("address", "")).trim());
    }

    @PostMapping("/demo")
    public Map<String, Object> demo() {
        return auth.demo();
    }

    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String ip = Auth.clientIp(request);
        if (!limiter.allow("verify:" + ip, props.getAuthRateLimitMax())) {
            throw new ApiException(429, "Too many sign-in attempts. Try again in a few minutes.", "RATE_LIMITED");
        }
        try {
            Map<String, Object> session = auth.verify(
                    body == null ? null : string(body.get("address")),
                    body == null ? null : string(body.get("message")),
                    body == null ? null : string(body.get("signature")),
                    ip);
            limiter.refund("verify:" + ip);
            return session;
        } catch (RuntimeException ex) {
            throw ex;
        }
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        return auth.me(Auth.require(request).userId());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        return auth.logout(Auth.require(request).userId(), Auth.clientIp(request));
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
