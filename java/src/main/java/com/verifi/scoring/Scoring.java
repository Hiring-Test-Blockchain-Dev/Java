package com.verifi.scoring;

import com.verifi.support.Time;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Scoring {
    public static final String MODEL_VERSION = "1.0.0";
    private static final int APPROVE_THRESHOLD = 60;
    private static final double MIN_INCOME = 1500;
    private static final double MAX_DTI_PCT = 45;

    private Scoring() {}

    public static final class Fraud {
        public final String severity;
        public final String description;

        public Fraud(String severity, String description) {
            this.severity = severity;
            this.description = description;
        }
    }

    public static final class Input {
        public double monthlyIncomeEstimate;
        public double avgMonthlySpend;
        public double savingsRatePct;
        public Double budgetAdherencePct;
        public int overdraftCount;
        public double incomeVariancePct;
        public int consecutivePositiveMonths;
        public int negativeBalanceDays;
        public int transactionVolume90d;
        public List<Fraud> fraudFlags = List.of();
    }

    public static final class Result {
        public int riskScore;
        public String decision;
        public List<Map<String, String>> reasonCodes;
        public Map<String, Object> factors;
        public Integer recommendedLimit;
        public String modelVersion;
        public String timestamp;
    }

    public static Result calculate(Input input) {
        List<Map<String, String>> reasons = new ArrayList<>();
        double fraudPenalty = fraudPenalty(input.fraudFlags, reasons);
        Map<String, Double> weights = weights();
        if (fraudPenalty >= 100) {
            return build(100, "reject", factors(0, 0, 0, fraudPenalty, weights), reasons, null);
        }
        double incomeScore = incomeScore(input.monthlyIncomeEstimate, reasons);
        double spendingScore = spendingScore(input, reasons);
        double cashflowScore = cashflowScore(input, reasons);
        double raw = (100 - incomeScore) * weights.get("income")
                + (100 - spendingScore) * weights.get("spending")
                + (100 - cashflowScore) * weights.get("cashflow")
                + fraudPenalty;
        int risk = (int) Math.max(0, Math.min(100, Math.round(raw)));
        String decision = risk <= APPROVE_THRESHOLD ? "approve" : "reject";
        if (reasons.isEmpty()) reasons.add(reason("D000", "Standard eligibility assessment", "neutral"));
        Integer limit = "approve".equals(decision) ? recommendedLimit(risk, input.monthlyIncomeEstimate) : null;
        return build(risk, decision, factors(incomeScore, spendingScore, cashflowScore, fraudPenalty, weights), reasons, limit);
    }

    private static Result build(int risk, String decision, Map<String, Object> factors, List<Map<String, String>> reasons, Integer limit) {
        Result result = new Result();
        result.riskScore = risk;
        result.decision = decision;
        result.reasonCodes = reasons;
        result.factors = factors;
        result.recommendedLimit = limit;
        result.modelVersion = MODEL_VERSION;
        result.timestamp = Time.format(Instant.now());
        return result;
    }

    private static Map<String, Object> factors(double income, double spending, double cashflow, double fraud, Map<String, Double> weights) {
        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("incomeScore", income);
        factors.put("spendingScore", spending);
        factors.put("cashflowScore", cashflow);
        factors.put("fraudPenalty", fraud);
        factors.put("weights", weights);
        return factors;
    }

    private static Map<String, Double> weights() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("income", 0.3);
        weights.put("spending", 0.35);
        weights.put("cashflow", 0.25);
        return weights;
    }

    private static double fraudPenalty(List<Fraud> flags, List<Map<String, String>> reasons) {
        if (flags == null || flags.isEmpty()) {
            reasons.add(reason("F001", "No fraud indicators present", "positive"));
            return 0;
        }
        double penalty = 0;
        for (Fraud flag : flags) {
            String severity = flag.severity == null ? "" : flag.severity;
            String description = flag.description == null || flag.description.isBlank() ? null : flag.description;
            switch (severity) {
                case "critical" -> {
                    reasons.add(reason("F104", description == null ? "Critical fraud flag - application declined" : description, "negative"));
                    return 100;
                }
                case "high" -> {
                    penalty += 40;
                    reasons.add(reason("F103", description == null ? "High-severity fraud flag present" : description, "negative"));
                }
                case "medium" -> {
                    penalty += 25;
                    reasons.add(reason("F102", description == null ? "Medium-severity fraud flag present" : description, "negative"));
                }
                case "low" -> {
                    penalty += 10;
                    reasons.add(reason("F101", description == null ? "Low-severity fraud flag present" : description, "negative"));
                }
                default -> penalty += 15;
            }
        }
        return Math.min(99, penalty);
    }

    private static double incomeScore(double monthlyIncome, List<Map<String, String>> reasons) {
        if (monthlyIncome >= MIN_INCOME) {
            reasons.add(reason("I001", "Monthly income meets minimum threshold", "positive"));
            return Math.min(100, 50 + (monthlyIncome - MIN_INCOME) / 100);
        }
        reasons.add(reason("I101", "Monthly income below minimum threshold", "negative"));
        return Math.max(0, (monthlyIncome / MIN_INCOME) * 50);
    }

    private static double spendingScore(Input input, List<Map<String, String>> reasons) {
        double score = 70;
        double spendRate = input.monthlyIncomeEstimate > 0 ? (input.avgMonthlySpend / input.monthlyIncomeEstimate) * 100 : 100;
        if (spendRate <= MAX_DTI_PCT) reasons.add(reason("I002", "Debt-to-income ratio within acceptable range", "positive"));
        else {
            reasons.add(reason("I102", "Debt-to-income ratio exceeds limit", "negative"));
            score -= 30;
        }
        if (input.savingsRatePct >= 5) {
            reasons.add(reason("S001", "Demonstrates savings behavior", "positive"));
            score = Math.min(100, score + 15);
        }
        double adherence = input.budgetAdherencePct == null ? 0 : input.budgetAdherencePct;
        if (adherence >= 70) {
            reasons.add(reason("S002", "Budget adherence observed", "positive"));
            score = Math.min(100, score + 10);
        }
        if (input.overdraftCount > 0) {
            reasons.add(reason("S102", "Overdraft activity detected", "negative"));
            score -= 20 * Math.min(3, input.overdraftCount);
        }
        if (spendRate > 100) {
            reasons.add(reason("S101", "Spending rate exceeds income", "negative"));
            score -= 25;
        }
        return Math.max(0, Math.min(100, score));
    }

    private static double cashflowScore(Input input, List<Map<String, String>> reasons) {
        double score = 60;
        if (input.transactionVolume90d < 10) {
            reasons.add(reason("C103", "Insufficient transaction history", "negative"));
            score -= 30;
        } else if (input.consecutivePositiveMonths >= 2) {
            reasons.add(reason("C001", "Consistent positive cashflow over observation period", "positive"));
            score += 25;
        } else if (input.incomeVariancePct > 30) {
            reasons.add(reason("C101", "High variance in monthly cashflow", "negative"));
            score -= 20;
        }
        if (input.negativeBalanceDays > 0) {
            reasons.add(reason("C102", "Negative balance days in observation period", "negative"));
            score -= 15 * Math.min(5, input.negativeBalanceDays);
        }
        return Math.max(0, Math.min(100, score));
    }

    private static int recommendedLimit(int riskScore, double monthlyIncome) {
        double maxByIncome = monthlyIncome * 0.25;
        double maxByRisk = 5000 - (riskScore / 100.0) * 3000;
        return (int) Math.round(Math.min(5000, Math.max(500, Math.min(maxByIncome, maxByRisk))));
    }

    private static Map<String, String> reason(String code, String description, String impact) {
        Map<String, String> reason = new LinkedHashMap<>();
        reason.put("code", code);
        reason.put("description", description);
        reason.put("impact", impact);
        return reason;
    }
}
