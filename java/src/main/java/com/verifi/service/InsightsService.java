package com.verifi.service;

import com.verifi.support.Time;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class InsightsService {
    private final JdbcTemplate jdbc;

    public InsightsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> monthly(String userId, Integer monthParam, Integer yearParam) {
        LocalDate today = LocalDate.now();
        int month = monthParam == null ? today.getMonthValue() : monthParam;
        int year = yearParam == null ? today.getYear() : yearParam;
        ZoneId zone = ZoneId.systemDefault();
        String start = Time.format(LocalDate.of(year, month, 1).atStartOfDay(zone).toInstant());
        String end = Time.format(LocalDate.of(year, month, 1).plusMonths(1).atStartOfDay(zone).toInstant());
        List<String> accountIds = jdbc.query("SELECT id FROM accounts WHERE user_id = ? AND is_active = 1", (rs, n) -> rs.getString(1), userId);
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("month", month);
        empty.put("year", year);
        empty.put("totalSpent", 0);
        empty.put("totalBudgeted", 0);
        empty.put("byCategory", List.of());
        empty.put("monthlyTrend", List.of());
        if (accountIds.isEmpty()) return empty;

        String in = placeholders(accountIds.size());
        List<Object> txArgs = new ArrayList<>();
        txArgs.addAll(accountIds);
        txArgs.add(start);
        txArgs.add(end);
        List<Map<String, Object>> txs = jdbc.query("""
                SELECT category, amount FROM transactions
                WHERE account_id IN (%s) AND date >= ? AND date < ? AND amount < 0
                """.formatted(in), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", rs.getString("category"));
            row.put("amount", rs.getDouble("amount"));
            return row;
        }, txArgs.toArray());
        List<Object> budgetArgs = new ArrayList<>();
        budgetArgs.add(userId);
        budgetArgs.add(end);
        budgetArgs.add(start);
        List<Map<String, Object>> budgets = jdbc.query("""
                SELECT category, amount, name FROM budgets
                WHERE user_id = ? AND start_date <= ? AND (end_date IS NULL OR end_date >= ?)
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", rs.getString("category"));
            row.put("amount", rs.getDouble("amount"));
            row.put("name", rs.getString("name"));
            return row;
        }, budgetArgs.toArray());

        Map<String, Double> spend = new LinkedHashMap<>();
        for (Map<String, Object> tx : txs) {
            String category = tx.get("category") == null ? "Uncategorized" : tx.get("category").toString();
            spend.merge(category, Math.abs(((Number) tx.get("amount")).doubleValue()), Double::sum);
        }
        Map<String, Map<String, Object>> budgetMap = new LinkedHashMap<>();
        for (Map<String, Object> budget : budgets) budgetMap.put((String) budget.get("category"), budget);
        List<Map<String, Object>> byCategory = new ArrayList<>();
        for (var entry : spend.entrySet()) {
            Map<String, Object> budget = budgetMap.get(entry.getKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", entry.getKey());
            row.put("spent", entry.getValue());
            row.put("budget", budget == null ? null : budget.get("amount"));
            row.put("overBudget", budget != null && entry.getValue() > ((Number) budget.get("amount")).doubleValue());
            row.put("budgetName", budget == null ? null : budget.get("name"));
            byCategory.add(row);
        }
        byCategory.sort(Comparator.comparingDouble((Map<String, Object> row) -> ((Number) row.get("spent")).doubleValue()).reversed());
        double totalSpent = byCategory.stream().mapToDouble(row -> ((Number) row.get("spent")).doubleValue()).sum();
        double totalBudgeted = budgetMap.values().stream().mapToDouble(row -> ((Number) row.get("amount")).doubleValue()).sum();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("month", month);
        body.put("year", year);
        body.put("totalSpent", totalSpent);
        body.put("totalBudgeted", totalBudgeted);
        body.put("byCategory", byCategory);
        body.put("monthlyTrend", trend(accountIds, year, month, zone));
        return body;
    }

    private List<Map<String, Object>> trend(List<String> accountIds, int year, int month, ZoneId zone) {
        List<Map<String, Object>> trend = new ArrayList<>();
        String in = placeholders(accountIds.size());
        for (int i = 2; i >= 0; i--) {
            int mo = month - i;
            int y = year;
            if (mo < 1) { mo += 12; y -= 1; }
            else if (mo > 12) { mo -= 12; y += 1; }
            String start = Time.format(LocalDate.of(y, mo, 1).atStartOfDay(zone).toInstant());
            String end = Time.format(LocalDate.of(y, mo, 1).plusMonths(1).atStartOfDay(zone).toInstant());
            List<Object> args = new ArrayList<>();
            args.addAll(accountIds);
            args.add(start);
            args.add(end);
            List<Double> amounts = jdbc.query("SELECT amount FROM transactions WHERE account_id IN (" + in + ") AND date >= ? AND date < ?",
                    (rs, n) -> rs.getDouble(1), args.toArray());
            double spent = amounts.stream().filter(a -> a < 0).mapToDouble(Math::abs).sum();
            double credits = amounts.stream().filter(a -> a > 0).mapToDouble(Double::doubleValue).sum();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", Month.of(mo).getDisplayName(TextStyle.SHORT, Locale.US));
            row.put("spent", Math.round(spent * 100) / 100.0);
            row.put("credits", Math.round(credits * 100) / 100.0);
            trend.add(row);
        }
        return trend;
    }

    private static String placeholders(int count) {
        return "?,".repeat(count).substring(0, count * 2 - 1);
    }
}
