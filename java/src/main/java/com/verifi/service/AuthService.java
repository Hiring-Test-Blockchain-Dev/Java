package com.verifi.service;

import com.verifi.error.ApiException;
import com.verifi.security.JwtService;
import com.verifi.security.NonceService;
import com.verifi.security.WalletSignatureVerifier;
import com.verifi.config.AppProperties;
import com.verifi.support.Body;
import com.verifi.support.Ids;
import com.verifi.support.Time;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final NonceService nonces;
    private final WalletSignatureVerifier signatures;
    private final AuditService audit;
    private final AppProperties props;

    public AuthService(JdbcTemplate jdbc, JwtService jwt, NonceService nonces, WalletSignatureVerifier signatures, AuditService audit, AppProperties props) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.nonces = nonces;
        this.signatures = signatures;
        this.audit = audit;
        this.props = props;
    }

    public Map<String, Object> nonce(String address) {
        String valid = requireAddress(address);
        return Map.of("nonce", nonces.create(valid));
    }

    public Map<String, Object> demo() {
        if (props.isProduction()) throw new ApiException(404, "Not found", "NOT_FOUND");
        Map<String, Object> user = findActiveByWallet(DatabaseSeeder.DEMO_WALLET);
        if (user == null) user = createWalletUser(DatabaseSeeder.DEMO_WALLET);
        return session(user);
    }

    public Map<String, Object> verify(String address, String message, String signature, String ip) {
        String addr = requireAddress(address);
        if (message == null || message.isBlank() || signature == null || signature.isBlank()) {
            throw new ApiException(400, "message: Invalid value", "VALIDATION_ERROR");
        }
        if (!nonces.consume(addr, message.trim())) {
            throw new ApiException(401, "Invalid or expired nonce", "INVALID_NONCE");
        }
        if (!signatures.verify(addr, message.trim(), signature.trim())) {
            throw new ApiException(401, "Invalid signature", "INVALID_SIGNATURE");
        }
        Map<String, Object> user = findActiveByWallet(addr);
        if (user == null) user = createWalletUser(addr);
        audit.log((String) user.get("id"), "login", null, null, null, ip);
        return session(user);
    }

    public Map<String, Object> me(String userId) {
        Map<String, Object> user = jdbc.query("""
                SELECT id, wallet_address, email, first_name, last_name, role
                FROM users WHERE id = ?
                """, (rs, n) -> publicUser(rs), userId).stream().findFirst().orElse(null);
        if (user == null) throw new ApiException(401, "Unauthorized", "UNAUTHORIZED");
        return user;
    }

    public Map<String, Object> logout(String userId, String ip) {
        audit.log(userId, "logout", null, null, null, ip);
        return Map.of("ok", true);
    }

    private Map<String, Object> session(Map<String, Object> user) {
        String token = jwt.sign((String) user.get("id"), (String) user.get("email"), (String) user.get("role"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", user);
        body.put("token", token);
        body.put("accessToken", token);
        body.put("expiresIn", props.expirySeconds());
        return body;
    }

    private Map<String, Object> findActiveByWallet(String address) {
        String key = Body.walletKey(address);
        String sql = key.startsWith("0x")
                ? """
                SELECT id, wallet_address, email, first_name, last_name, role
                FROM users WHERE lower(wallet_address) = ? AND is_active = 1
                """
                : """
                SELECT id, wallet_address, email, first_name, last_name, role
                FROM users WHERE wallet_address = ? AND is_active = 1
                """;
        return jdbc.query(sql, (rs, n) -> publicUser(rs), key).stream().findFirst().orElse(null);
    }

    private Map<String, Object> createWalletUser(String address) {
        String id = Ids.uuid();
        String now = Time.now();
        String key = Body.walletKey(address);
        jdbc.update("""
                INSERT INTO users (id, wallet_address, email, password_hash, first_name, last_name, role, is_active, kyc_status, created_at, updated_at)
                VALUES (?, ?, NULL, NULL, NULL, NULL, 'user', 1, 'unverified', ?, ?)
                """, id, key, now, now);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", id);
        user.put("walletAddress", key);
        user.put("email", null);
        user.put("firstName", null);
        user.put("lastName", null);
        user.put("role", "user");
        return user;
    }

    private static Map<String, Object> publicUser(ResultSet rs) throws SQLException {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", rs.getString("id"));
        user.put("walletAddress", rs.getString("wallet_address"));
        user.put("email", rs.getString("email"));
        user.put("firstName", rs.getString("first_name"));
        user.put("lastName", rs.getString("last_name"));
        user.put("role", rs.getString("role"));
        return user;
    }

    private static String requireAddress(String address) {
        if (address == null || address.trim().length() < 32 || address.trim().length() > 64) {
            throw new ApiException(400, "address: Invalid value", "VALIDATION_ERROR");
        }
        return address.trim();
    }
}
