package com.verifi.service;

import com.verifi.config.AppProperties;
import com.verifi.error.ApiException;
import com.verifi.support.Body;
import com.verifi.support.Ids;
import com.verifi.support.Time;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KycService {
    private static final Set<String> DOCUMENTS = Set.of("passport", "drivers_license", "national_id");
    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public KycService(JdbcTemplate jdbc, AppProperties props, AuditService audit, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
        this.tx = tx;
    }

    public Map<String, Object> me(String userId) {
        String status = jdbc.query("SELECT kyc_status FROM users WHERE id = ?", (rs, n) -> rs.getString(1), userId)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "User not found", "NOT_FOUND"));
        Map<String, Object> latest = jdbc.query("""
                SELECT * FROM kyc_submissions WHERE user_id = ? ORDER BY submitted_at DESC LIMIT 1
                """, (rs, n) -> submission(rs), userId).stream().findFirst().orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("autoApprove", props.isKycAutoApprove());
        body.put("submission", latest);
        return body;
    }

    public Map<String, Object> submit(String userId, Map<String, Object> body) {
        String fullName = Body.text(body, "fullName");
        String dobRaw = Body.text(body, "dateOfBirth");
        String country = Body.text(body, "country");
        String documentType = Body.text(body, "documentType");
        String last4 = Body.text(body, "documentLast4");
        if (fullName == null || fullName.isEmpty() || fullName.length() > 200) throw new ApiException(400, "Full name required", "VALIDATION_ERROR");
        if (dobRaw == null || dobRaw.isBlank()) throw new ApiException(400, "Valid date of birth required", "VALIDATION_ERROR");
        if (country == null || country.isEmpty() || country.length() > 80) throw new ApiException(400, "Country required", "VALIDATION_ERROR");
        if (documentType == null || !DOCUMENTS.contains(documentType)) {
            throw new ApiException(400, "Document type must be passport, drivers_license, or national_id", "VALIDATION_ERROR");
        }
        if (last4 != null && last4.length() != 4) throw new ApiException(400, "Use last 4 characters only", "VALIDATION_ERROR");
        LocalDate dob = LocalDate.parse(dobRaw.substring(0, 10));
        if (dob.isAfter(LocalDate.now(ZoneOffset.UTC))) throw new ApiException(400, "Invalid date of birth", "VALIDATION_ERROR");
        double ageYears = (Instant.now().toEpochMilli() - dob.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()) / (365.25 * 24 * 60 * 60 * 1000);
        if (ageYears < 18) throw new ApiException(400, "You must be at least 18 to verify", "KYC_AGE");
        boolean auto = props.isKycAutoApprove();
        String status = auto ? "verified" : "pending";
        String id = Ids.uuid();
        String now = Time.now();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO kyc_submissions
                    (id, user_id, full_name, date_of_birth, country, document_type, document_last4, status, review_notes, submitted_at, reviewed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, userId, fullName, dob.toString(), country, documentType, last4, status,
                    auto ? "Auto-approved in demo mode" : null, now, auto ? now : null);
            jdbc.update("UPDATE users SET kyc_status = ?, updated_at = ? WHERE id = ?", status, now, userId);
        });
        audit.log(userId, auto ? "kyc.auto_verified" : "kyc.submitted", "kyc", id, null, null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("submission", jdbc.query("SELECT * FROM kyc_submissions WHERE id = ?", (rs, n) -> submission(rs), id).get(0));
        response.put("message", auto ? "Identity verified (demo auto-approve)." : "Submitted for review. An admin will verify your identity.");
        return response;
    }

    public List<Map<String, Object>> adminList() {
        return jdbc.query("""
                SELECT k.*, u.id AS uid, u.wallet_address, u.email, u.kyc_status
                FROM kyc_submissions k JOIN users u ON u.id = k.user_id
                ORDER BY k.submitted_at DESC LIMIT 100
                """, (rs, n) -> {
            Map<String, Object> row = submission(rs);
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", rs.getString("uid"));
            user.put("walletAddress", rs.getString("wallet_address"));
            user.put("email", rs.getString("email"));
            user.put("kycStatus", rs.getString("kyc_status"));
            row.put("user", user);
            return row;
        });
    }

    public Map<String, Object> review(String adminId, String id, Map<String, Object> body) {
        Ids.checkUuid(id);
        String action = Body.text(body, "action");
        if (action == null || (!"approve".equals(action) && !"reject".equals(action))) {
            throw new ApiException(400, "action must be approve or reject", "VALIDATION_ERROR");
        }
        String notes = Body.text(body, "notes");
        if (notes != null && notes.length() > 500) throw new ApiException(400, "notes: Invalid value", "VALIDATION_ERROR");
        Map<String, Object> existing = jdbc.query("SELECT * FROM kyc_submissions WHERE id = ?", (rs, n) -> submission(rs), id)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "KYC submission not found", "NOT_FOUND"));
        String status = "approve".equals(action) ? "verified" : "rejected";
        String now = Time.now();
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE kyc_submissions SET status = ?, reviewed_at = ?, review_notes = ? WHERE id = ?", status, now, notes, id);
            jdbc.update("UPDATE users SET kyc_status = ?, updated_at = ? WHERE id = ?", status, now, existing.get("userId"));
        });
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subjectUserId", existing.get("userId"));
        details.put("notes", notes);
        audit.log(adminId, "kyc." + action, "kyc", id, details, null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("submission", jdbc.query("SELECT * FROM kyc_submissions WHERE id = ?", (rs, n) -> submission(rs), id).get(0));
        return response;
    }

    private static Map<String, Object> submission(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("userId", rs.getString("user_id"));
        row.put("fullName", rs.getString("full_name"));
        String dob = rs.getString("date_of_birth");
        row.put("dateOfBirth", dob != null && dob.length() >= 10 ? dob.substring(0, 10) : dob);
        row.put("country", rs.getString("country"));
        row.put("documentType", rs.getString("document_type"));
        row.put("documentLast4", rs.getString("document_last4"));
        row.put("status", rs.getString("status"));
        row.put("reviewNotes", rs.getString("review_notes"));
        row.put("submittedAt", rs.getString("submitted_at"));
        row.put("reviewedAt", rs.getString("reviewed_at"));
        return row;
    }
}
