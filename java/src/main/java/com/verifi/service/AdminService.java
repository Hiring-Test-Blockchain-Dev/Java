package com.verifi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifi.error.ApiException;
import com.verifi.support.Body;
import com.verifi.support.Ids;
import com.verifi.support.Time;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminService {
    private static final Map<String, String> STATUS = Map.of(
            "approve", "approved",
            "reject", "rejected",
            "disburse", "disbursed",
            "repay", "repaid",
            "default", "defaulted"
    );
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;

    public AdminService(JdbcTemplate jdbc, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    public List<Map<String, Object>> users() {
        return jdbc.query("""
                SELECT id, wallet_address, email, first_name, last_name, role, is_active, created_at
                FROM users ORDER BY created_at DESC
                """, (rs, n) -> {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", rs.getString("id"));
            user.put("walletAddress", rs.getString("wallet_address"));
            user.put("email", rs.getString("email"));
            user.put("firstName", rs.getString("first_name"));
            user.put("lastName", rs.getString("last_name"));
            user.put("role", rs.getString("role"));
            user.put("isActive", Body.bool(rs, "is_active"));
            user.put("createdAt", rs.getString("created_at"));
            return user;
        });
    }

    public List<Map<String, Object>> loans(String status) {
        String sql = """
                SELECT l.*, u.wallet_address, u.email, u.first_name, u.last_name
                FROM loans l JOIN users u ON u.id = l.user_id
                """;
        if (status != null && !status.isBlank()) sql += " WHERE l.status = ?";
        sql += " ORDER BY l.applied_at DESC";
        return status != null && !status.isBlank()
                ? jdbc.query(sql, (rs, n) -> adminLoan(rs), status)
                : jdbc.query(sql, (rs, n) -> adminLoan(rs));
    }

    public Map<String, Object> updateLoan(String adminId, String id, String action, String ip) {
        Ids.checkUuid(id);
        if (!STATUS.containsKey(action)) throw new ApiException(400, "action: Invalid value", "VALIDATION_ERROR");
        if (jdbc.query("SELECT id FROM loans WHERE id = ?", (rs, n) -> rs.getString(1), id).isEmpty()) {
            throw new ApiException(404, "Loan not found", "NOT_FOUND");
        }
        String now = Time.now();
        if ("approve".equals(action)) {
            jdbc.update("UPDATE loans SET status = ?, approved_at = ? WHERE id = ?", STATUS.get(action), now, id);
        } else if ("disburse".equals(action)) {
            jdbc.update("UPDATE loans SET status = ?, disbursed_at = ? WHERE id = ?", STATUS.get(action), now, id);
        } else {
            jdbc.update("UPDATE loans SET status = ? WHERE id = ?", STATUS.get(action), id);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", STATUS.get(action));
        audit.log(adminId, "loan_" + action, "loan", id, details, ip);
        return jdbc.query("SELECT * FROM loans WHERE id = ?", (rs, n) -> fullLoan(rs), id).get(0);
    }

    public List<Map<String, Object>> riskScores(int limit) {
        int take = Math.min(Math.max(limit, 1), 500);
        return jdbc.query("""
                SELECT r.*, u.wallet_address, u.email, u.first_name, u.last_name
                FROM loan_eligibility_results r LEFT JOIN users u ON u.id = r.user_id
                ORDER BY r.created_at DESC LIMIT ?
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getString("id"));
            row.put("userId", rs.getString("user_id"));
            row.put("riskScore", rs.getInt("risk_score"));
            row.put("decision", rs.getString("decision"));
            row.put("reasonCodes", parse(rs.getString("reason_codes")));
            row.put("factors", parse(rs.getString("factors")));
            row.put("recommendedLimit", Body.dbl(rs, "recommended_limit"));
            row.put("modelVersion", rs.getString("model_version"));
            row.put("chainId", Body.integer(rs, "chain_id"));
            row.put("attestationContract", rs.getString("attestation_contract"));
            row.put("attestationId", rs.getString("attestation_id"));
            row.put("attestationTxHash", rs.getString("attestation_tx_hash"));
            row.put("createdAt", rs.getString("created_at"));
            row.put("user", userBits(rs, true));
            return row;
        }, take);
    }

    public List<Map<String, Object>> fraud(int limit) {
        int take = Math.min(Math.max(limit, 1), 200);
        return jdbc.query("""
                SELECT f.*, u.wallet_address, u.email FROM fraud_signals f
                LEFT JOIN users u ON u.id = f.user_id
                ORDER BY f.created_at DESC LIMIT ?
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getString("id"));
            row.put("userId", rs.getString("user_id"));
            row.put("signalType", rs.getString("signal_type"));
            row.put("severity", rs.getString("severity"));
            row.put("description", rs.getString("description"));
            row.put("createdAt", rs.getString("created_at"));
            row.put("user", userBits(rs, false));
            return row;
        }, take);
    }

    public List<Map<String, Object>> auditLogs(int limit) {
        int take = Math.min(Math.max(limit, 1), 500);
        return jdbc.query("""
                SELECT a.*, u.email, u.wallet_address FROM audit_logs a
                LEFT JOIN users u ON u.id = a.user_id
                ORDER BY a.created_at DESC LIMIT ?
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getString("id"));
            row.put("userId", rs.getString("user_id"));
            row.put("action", rs.getString("action"));
            row.put("resourceType", rs.getString("resource_type"));
            row.put("resourceId", rs.getString("resource_id"));
            row.put("details", parse(rs.getString("details")));
            row.put("ipAddress", rs.getString("ip_address"));
            row.put("createdAt", rs.getString("created_at"));
            String email = rs.getString("email");
            row.put("userEmail", email != null ? email : rs.getString("wallet_address"));
            return row;
        }, take);
    }

    private Object parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return json.readValue(raw, Object.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Map<String, Object> adminLoan(ResultSet rs) throws SQLException {
        Map<String, Object> loan = LoanService.loan(rs);
        loan.put("userId", rs.getString("user_id"));
        loan.put("approvedAt", rs.getString("approved_at"));
        loan.put("disbursedAt", rs.getString("disbursed_at"));
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", rs.getString("user_id"));
        user.put("walletAddress", rs.getString("wallet_address"));
        user.put("email", rs.getString("email"));
        user.put("firstName", rs.getString("first_name"));
        user.put("lastName", rs.getString("last_name"));
        loan.put("user", user);
        return loan;
    }

    private static Map<String, Object> fullLoan(ResultSet rs) throws SQLException {
        Map<String, Object> loan = LoanService.loan(rs);
        loan.put("userId", rs.getString("user_id"));
        loan.put("approvedAt", rs.getString("approved_at"));
        loan.put("disbursedAt", rs.getString("disbursed_at"));
        return loan;
    }

    private static Map<String, Object> userBits(ResultSet rs, boolean names) throws SQLException {
        if (rs.getString("wallet_address") == null && rs.getString("email") == null && !names) return null;
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("walletAddress", rs.getString("wallet_address"));
        user.put("email", rs.getString("email"));
        if (names) {
            user.put("firstName", rs.getString("first_name"));
            user.put("lastName", rs.getString("last_name"));
        }
        return user;
    }
}
