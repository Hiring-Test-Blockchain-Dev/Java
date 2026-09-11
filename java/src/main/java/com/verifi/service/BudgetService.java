package com.verifi.service;

import com.verifi.error.ApiException;
import com.verifi.support.Body;
import com.verifi.support.Ids;
import com.verifi.support.Time;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class BudgetService {
    private static final Set<String> PERIODS = Set.of("weekly", "biweekly", "monthly", "yearly");
    private final JdbcTemplate jdbc;

    public BudgetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(String userId) {
        return jdbc.query("SELECT * FROM budgets WHERE user_id = ? ORDER BY start_date DESC", (rs, n) -> budget(rs), userId);
    }

    public Map<String, Object> get(String userId, String id) {
        Ids.checkUuid(id);
        return one(userId, id);
    }

    public Map<String, Object> create(String userId, Map<String, Object> body) {
        String name = Body.text(body, "name");
        String category = Body.text(body, "category");
        Double amount = Body.decimal(body, "amount");
        String start = Body.text(body, "startDate");
        String period = Body.text(body, "period");
        if (name == null || name.isEmpty()) throw new ApiException(400, "Name required", "VALIDATION_ERROR");
        if (name.length() > 255) throw new ApiException(400, "name: Invalid value", "VALIDATION_ERROR");
        if (category == null || category.isEmpty()) throw new ApiException(400, "Category required", "VALIDATION_ERROR");
        if (category.length() > 100) throw new ApiException(400, "category: Invalid value", "VALIDATION_ERROR");
        if (amount == null || amount < 0) throw new ApiException(400, "Valid positive amount required", "VALIDATION_ERROR");
        if (period != null && !PERIODS.contains(period)) throw new ApiException(400, "period: Invalid value", "VALIDATION_ERROR");
        if (start == null || start.isBlank()) throw new ApiException(400, "Valid start date required", "VALIDATION_ERROR");
        String end = Body.text(body, "endDate");
        String id = Ids.uuid();
        String now = Time.now();
        jdbc.update("""
                INSERT INTO budgets (id, user_id, name, category, amount, period, start_date, end_date, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, name, category, amount, period == null ? "monthly" : period,
                Time.format(Time.parseClient(start)), end == null || end.isBlank() ? null : Time.format(Time.parseClient(end)), now, now);
        return one(userId, id);
    }

    public Map<String, Object> update(String userId, String id, Map<String, Object> body) {
        Ids.checkUuid(id);
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (body.get("name") != null) {
            String name = Body.text(body, "name");
            if (name.length() > 255) throw new ApiException(400, "name: Invalid value", "VALIDATION_ERROR");
            sets.add("name = ?");
            args.add(name);
        }
        if (body.get("amount") != null) {
            Double amount = Body.decimal(body, "amount");
            if (amount == null || amount < 0) throw new ApiException(400, "Valid positive amount required", "VALIDATION_ERROR");
            sets.add("amount = ?");
            args.add(amount);
        }
        if (body.containsKey("endDate")) {
            String end = Body.text(body, "endDate");
            sets.add("end_date = ?");
            args.add(end == null || end.isBlank() ? null : Time.format(Time.parseClient(end)));
        }
        sets.add("updated_at = ?");
        args.add(Time.now());
        args.add(id);
        args.add(userId);
        int updated = jdbc.update("UPDATE budgets SET " + String.join(", ", sets) + " WHERE id = ? AND user_id = ?", args.toArray());
        if (updated == 0) throw new ApiException(404, "Budget not found", "NOT_FOUND");
        return one(userId, id);
    }

    public void delete(String userId, String id) {
        Ids.checkUuid(id);
        int deleted = jdbc.update("DELETE FROM budgets WHERE id = ? AND user_id = ?", id, userId);
        if (deleted == 0) throw new ApiException(404, "Budget not found", "NOT_FOUND");
    }

    private Map<String, Object> one(String userId, String id) {
        return jdbc.query("SELECT * FROM budgets WHERE id = ? AND user_id = ?", (rs, n) -> budget(rs), id, userId)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "Budget not found", "NOT_FOUND"));
    }

    private static Map<String, Object> budget(ResultSet rs) throws SQLException {
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("id", rs.getString("id"));
        budget.put("userId", rs.getString("user_id"));
        budget.put("name", rs.getString("name"));
        budget.put("category", rs.getString("category"));
        budget.put("amount", rs.getDouble("amount"));
        budget.put("period", rs.getString("period"));
        budget.put("startDate", rs.getString("start_date"));
        budget.put("endDate", rs.getString("end_date"));
        budget.put("createdAt", rs.getString("created_at"));
        budget.put("updatedAt", rs.getString("updated_at"));
        return budget;
    }
}
