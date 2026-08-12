package com.verifi.service;

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
public class LoanService {
    private static final double RATE = 12.99;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public LoanService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Map<String, Object> apply(String userId, Map<String, Object> body, String ip) {
        Double amount = Body.decimal(body, "amount");
        Integer term = Body.integer(body, "termMonths");
        if (amount == null || amount < 100 || amount > 50000) {
            throw new ApiException(400, "Loan amount should be between $100 and $50,000", "VALIDATION_ERROR");
        }
        if (term == null || term < 1 || term > 60) {
            throw new ApiException(400, "Please choose a term between 1 and 60 months", "VALIDATION_ERROR");
        }
        Map<String, Object> user = jdbc.query("SELECT wallet_address, kyc_status FROM users WHERE id = ?", (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("walletAddress", rs.getString("wallet_address"));
            row.put("kycStatus", rs.getString("kyc_status"));
            return row;
        }, userId).stream().findFirst().orElseThrow(() -> new ApiException(404, "User not found", "NOT_FOUND"));
        if (!"verified".equals(user.get("kycStatus"))) {
            throw new ApiException(403, "Complete identity verification before applying for a loan.", "KYC_REQUIRED");
        }
        double monthly = Math.round(payment(amount, RATE, term) * 100) / 100.0;
        String id = Ids.uuid();
        String now = Time.now();
        jdbc.update("""
                INSERT INTO loans (id, user_id, amount, interest_rate_pct, term_months, monthly_payment, status, applied_at)
                VALUES (?, ?, ?, ?, ?, ?, 'pending', ?)
                """, id, userId, amount, RATE, term, monthly, now);
        audit.log(userId, "loan_apply", "loan", id, Map.of("amount", amount, "termMonths", term), ip);
        Map<String, Object> response = jdbc.query("SELECT * FROM loans WHERE id = ?", (rs, n) -> loan(rs), id).get(0);
        response.put("message", "We've got your application. We'll review it and get back to you soon.");
        return response;
    }

    public List<Map<String, Object>> list(String userId) {
        return jdbc.query("SELECT * FROM loans WHERE user_id = ? ORDER BY applied_at DESC", (rs, n) -> loan(rs), userId);
    }

    public Map<String, Object> get(String userId, String id) {
        return jdbc.query("SELECT * FROM loans WHERE id = ? AND user_id = ?", (rs, n) -> loan(rs), id, userId)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "We couldn't find that loan.", "NOT_FOUND"));
    }

    static Map<String, Object> loan(ResultSet rs) throws SQLException {
        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("id", rs.getString("id"));
        loan.put("amount", rs.getDouble("amount"));
        loan.put("interestRatePct", rs.getDouble("interest_rate_pct"));
        loan.put("termMonths", rs.getInt("term_months"));
        loan.put("monthlyPayment", rs.getDouble("monthly_payment"));
        loan.put("status", rs.getString("status"));
        loan.put("appliedAt", rs.getString("applied_at"));
        loan.put("chainId", Body.integer(rs, "chain_id"));
        loan.put("contractAddress", rs.getString("contract_address"));
        loan.put("onChainLoanId", rs.getString("on_chain_loan_id"));
        loan.put("createTxHash", rs.getString("create_tx_hash"));
        loan.put("statusTxHash", rs.getString("status_tx_hash"));
        return loan;
    }

    private static double payment(double principal, double annualRatePct, int termMonths) {
        if (annualRatePct == 0) return principal / termMonths;
        double monthlyRate = annualRatePct / 100.0 / 12.0;
        double pow = Math.pow(1 + monthlyRate, termMonths);
        return (principal * monthlyRate * pow) / (pow - 1);
    }
}
