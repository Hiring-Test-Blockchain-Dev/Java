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

@Service
public class SavingsService {
    private final JdbcTemplate jdbc;

    public SavingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(String userId) {
        return jdbc.query("SELECT * FROM savings_goals WHERE user_id = ? ORDER BY created_at DESC", (rs, n) -> goal(rs), userId);
    }

    public Map<String, Object> create(String userId, Map<String, Object> body) {
        String name = Body.text(body, "name");
        Double target = Body.decimal(body, "targetAmount");
        Double current = Body.decimal(body, "currentAmount");
        if (name == null || name.isEmpty() || name.length() > 255) throw new ApiException(400, "Name required", "VALIDATION_ERROR");
        if (target == null || target < 0.01) throw new ApiException(400, "Valid target amount required", "VALIDATION_ERROR");
        if (current != null && current < 0) throw new ApiException(400, "currentAmount: Invalid value", "VALIDATION_ERROR");
        String targetDate = Body.text(body, "targetDate");
        String id = Ids.uuid();
        String now = Time.now();
        jdbc.update("""
                INSERT INTO savings_goals (id, user_id, name, target_amount, current_amount, target_date, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, name, target, current == null ? 0 : current,
                targetDate == null || targetDate.isBlank() ? null : Time.format(Time.parseClient(targetDate)), now, now);
        return one(id, userId);
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
        if (body.get("targetAmount") != null) {
            Double target = Body.decimal(body, "targetAmount");
            if (target == null || target < 0.01) throw new ApiException(400, "Valid target amount required", "VALIDATION_ERROR");
            sets.add("target_amount = ?");
            args.add(target);
        }
        if (body.get("currentAmount") != null) {
            Double current = Body.decimal(body, "currentAmount");
            if (current == null || current < 0) throw new ApiException(400, "currentAmount: Invalid value", "VALIDATION_ERROR");
            sets.add("current_amount = ?");
            args.add(current);
        }
        if (body.containsKey("targetDate")) {
            String date = Body.text(body, "targetDate");
            sets.add("target_date = ?");
            args.add(date == null || date.isBlank() ? null : Time.format(Time.parseClient(date)));
        }
        sets.add("updated_at = ?");
        args.add(Time.now());
        args.add(id);
        args.add(userId);
        int updated = jdbc.update("UPDATE savings_goals SET " + String.join(", ", sets) + " WHERE id = ? AND user_id = ?", args.toArray());
        if (updated == 0) throw new ApiException(404, "Savings goal not found", "NOT_FOUND");
        return one(id, userId);
    }

    public void delete(String userId, String id) {
        Ids.checkUuid(id);
        int deleted = jdbc.update("DELETE FROM savings_goals WHERE id = ? AND user_id = ?", id, userId);
        if (deleted == 0) throw new ApiException(404, "Savings goal not found", "NOT_FOUND");
    }

    private Map<String, Object> one(String id, String userId) {
        return jdbc.query("SELECT * FROM savings_goals WHERE id = ? AND user_id = ?", (rs, n) -> goal(rs), id, userId)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "Savings goal not found", "NOT_FOUND"));
    }

    private static Map<String, Object> goal(ResultSet rs) throws SQLException {
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("id", rs.getString("id"));
        goal.put("userId", rs.getString("user_id"));
        goal.put("name", rs.getString("name"));
        goal.put("targetAmount", rs.getDouble("target_amount"));
        goal.put("currentAmount", rs.getDouble("current_amount"));
        goal.put("targetDate", rs.getString("target_date"));
        goal.put("createdAt", rs.getString("created_at"));
        goal.put("updatedAt", rs.getString("updated_at"));
        return goal;
    }
}
