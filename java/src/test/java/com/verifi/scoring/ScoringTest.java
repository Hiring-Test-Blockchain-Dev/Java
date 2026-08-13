package com.verifi.scoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScoringTest {
    @Test
    void approvesStableIncome() {
        Scoring.Input input = new Scoring.Input();
        input.monthlyIncomeEstimate = 5000;
        input.avgMonthlySpend = 1000;
        input.savingsRatePct = 20;
        input.budgetAdherencePct = 80d;
        input.overdraftCount = 0;
        input.incomeVariancePct = 10;
        input.consecutivePositiveMonths = 3;
        input.negativeBalanceDays = 0;
        input.transactionVolume90d = 30;
        Scoring.Result result = Scoring.calculate(input);
        assertEquals(10, result.riskScore);
        assertEquals("approve", result.decision);
        assertEquals(1250, result.recommendedLimit);
    }

    @Test
    void rejectsCriticalFraud() {
        Scoring.Input input = new Scoring.Input();
        input.fraudFlags = java.util.List.of(new Scoring.Fraud("critical", "stolen card"));
        Scoring.Result result = Scoring.calculate(input);
        assertEquals(100, result.riskScore);
        assertEquals("reject", result.decision);
        assertNull(result.recommendedLimit);
        assertEquals("F104", result.reasonCodes.get(0).get("code"));
    }
}
