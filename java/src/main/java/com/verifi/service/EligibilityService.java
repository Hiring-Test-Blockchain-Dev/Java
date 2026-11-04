package com.verifi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifi.scoring.Scoring;
import com.verifi.support.Body;
import com.verifi.support.Ids;
import com.verifi.support.Time;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EligibilityService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public EligibilityService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Map<String, Object> score(String userId, Double monthlyIncomeOverride) {
        Financial data = load(userId);
        double income = monthlyIncomeOverride != null ? monthlyIncomeOverride : deriveIncome(data);
        double avgSpend = data.totalSpend90d / 3.0;
        double avgCredits = data.totalCredits90d / 3.0;
        double savingsRate = avgCredits > 0 ? Math.max(0, ((avgCredits - avgSpend) / avgCredits) * 100) : 0;
        Scoring.Input input = new Scoring.Input();
        input.monthlyIncomeEstimate = income;
        input.avgMonthlySpend = avgSpend;
        input.savingsRatePct = savingsRate;
        input.overdraftCount = data.overdraftCount;
        input.budgetAdherencePct = data.budgetCount > 0 ? data.budgetAdherencePct : null;
        input.incomeVariancePct = variance(data.monthlyCredits);
        input.consecutivePositiveMonths = consecutive(data.monthlyCredits, data.monthlyDebits);
        input.negativeBalanceDays = data.negativeBalanceDays;
        input.transactionVolume90d = data.transactionCount90d;
        input.fraudFlags = data.fraudFlags;
        Scoring.Result result = Scoring.calculate(input);

        String id = Ids.uuid();
        String now = Time.now();
        jdbc.update("""
                INSERT INTO loan_eligibility_results
                (id, user_id, risk_score, decision, reason_codes, factors, recommended_limit, model_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, result.riskScore, result.decision, write(result.reasonCodes), write(result.factors), result.recommendedLimit, result.modelVersion, now);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("riskScore", result.riskScore);
        body.put("decision", result.decision);
        body.put("reasonCodes", result.reasonCodes);
        body.put("factors", result.factors);
        if (result.recommendedLimit != null) body.put("recommendedLimit", result.recommendedLimit);
        body.put("modelVersion", result.modelVersion);
        body.put("timestamp", result.timestamp);
        body.put("attestation", null);
        return body;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private Financial load(String userId) {
        ZoneId zone = ZoneId.systemDefault();
        Instant ninety = ZonedDateTime.now(zone).minusDays(90).toInstant();
        Financial data = new Financial();
        List<Map<String, Object>> accounts = jdbc.query("""
                SELECT current_balance FROM accounts WHERE user_id = ? AND is_active = 1
                """, (rs, n) -> mapBalance(rs), userId);
        data.totalBalance = accounts.stream().mapToDouble(a -> (Double) a.get("balance")).sum();
        List<Tx> txs = jdbc.query("""
                SELECT t.amount, t.date FROM transactions t
                JOIN accounts a ON a.id = t.account_id
                WHERE a.user_id = ? ORDER BY t.date ASC
                """, (rs, n) -> new Tx(rs.getDouble("amount"), Time.parseStored(rs.getString("date"))), userId);
        List<Tx> recent = txs.stream().filter(tx -> !tx.date.isBefore(ninety)).toList();
        data.transactionCount90d = recent.size();
        data.totalSpend90d = recent.stream().filter(tx -> tx.amount < 0).mapToDouble(tx -> Math.abs(tx.amount)).sum();
        data.totalCredits90d = recent.stream().filter(tx -> tx.amount > 0).mapToDouble(tx -> tx.amount).sum();
        for (int i = 2; i >= 0; i--) {
            ZonedDateTime start = LocalDate.now(zone).minusMonths(i).withDayOfMonth(1).atStartOfDay(zone);
            Instant from = start.toInstant();
            Instant to = start.plusMonths(1).toInstant();
            List<Tx> month = recent.stream().filter(tx -> !tx.date.isBefore(from) && tx.date.isBefore(to)).toList();
            data.monthlyCredits.add(month.stream().filter(tx -> tx.amount > 0).mapToDouble(tx -> tx.amount).sum());
            data.monthlyDebits.add(month.stream().filter(tx -> tx.amount < 0).mapToDouble(tx -> Math.abs(tx.amount)).sum());
        }
        data.negativeBalanceDays = data.totalBalance < 0 ? 30 : 0;
        String now = Time.now();
        List<Double> budgets = jdbc.query("""
                SELECT amount FROM budgets
                WHERE user_id = ? AND start_date <= ? AND (end_date IS NULL OR end_date >= ?)
                """, (rs, n) -> rs.getDouble(1), userId, now, now);
        data.budgetCount = budgets.size();
        if (!budgets.isEmpty()) {
            ZonedDateTime monthStart = LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone);
            double monthSpend = recent.stream()
                    .filter(tx -> !tx.date.isBefore(monthStart.toInstant()) && tx.amount < 0)
                    .mapToDouble(tx -> Math.abs(tx.amount)).sum();
            int under = 0;
            for (Double amount : budgets) {
                if (monthSpend / budgets.size() <= amount) under++;
            }
            data.budgetAdherencePct = (double) Math.round((under * 100.0) / budgets.size());
        }
        data.fraudFlags = jdbc.query("""
                SELECT signal_type, severity, description FROM fraud_signals
                WHERE user_id = ? ORDER BY created_at DESC LIMIT 20
                """, (rs, n) -> new Scoring.Fraud(rs.getString("severity"), rs.getString("description")), userId);
        return data;
    }

    private static Map<String, Object> mapBalance(ResultSet rs) throws SQLException {
        return Map.of("balance", Body.dbl(rs, "current_balance") == null ? 0d : Body.dbl(rs, "current_balance"));
    }

    private static double deriveIncome(Financial data) {
        double avg = data.totalCredits90d / 3.0;
        double last = data.monthlyCredits.isEmpty() ? 0 : data.monthlyCredits.get(data.monthlyCredits.size() - 1);
        return last > 0 ? last : avg;
    }

    private static double variance(List<Double> values) {
        if (values.size() < 2) return 0;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum() / values.size();
        double std = Math.sqrt(variance);
        return mean > 0 ? Math.min(100, (std / mean) * 100) : 100;
    }

    private static int consecutive(List<Double> credits, List<Double> debits) {
        int count = 0;
        for (int i = credits.size() - 1; i >= 0; i--) {
            double net = credits.get(i) - debits.get(i);
            if (net >= 0) count++;
            else break;
        }
        return count;
    }

    private static final class Tx {
        final double amount;
        final Instant date;
        Tx(double amount, Instant date) { this.amount = amount; this.date = date; }
    }

    private static final class Financial {
        double totalBalance;
        int transactionCount90d;
        double totalSpend90d;
        double totalCredits90d;
        List<Double> monthlyCredits = new ArrayList<>();
        List<Double> monthlyDebits = new ArrayList<>();
        int overdraftCount;
        int negativeBalanceDays;
        int budgetCount;
        double budgetAdherencePct;
        List<Scoring.Fraud> fraudFlags = List.of();
    }
}
