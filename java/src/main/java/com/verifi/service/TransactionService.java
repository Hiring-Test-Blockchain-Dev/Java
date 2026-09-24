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
public class TransactionService {
    private final JdbcTemplate jdbc;
    private final CategorizationService categorization;

    public TransactionService(JdbcTemplate jdbc, CategorizationService categorization) {
        this.jdbc = jdbc;
        this.categorization = categorization;
    }

    public List<String> categories(String userId) {
        return jdbc.query("""
                SELECT DISTINCT t.category FROM transactions t
                JOIN accounts a ON a.id = t.account_id
                WHERE a.user_id = ? AND t.category IS NOT NULL
                ORDER BY t.category ASC
                """, (rs, n) -> rs.getString(1), userId);
    }

    public List<Map<String, Object>> list(String userId, String accountId, String startDate, String endDate, String category, Integer limit) {
        if (accountId != null && !accountId.isBlank()) Ids.checkUuid(accountId);
        List<String> accountIds = accountIds(userId);
        if (accountIds.isEmpty()) return List.of();
        if (accountId != null && !accountId.isBlank() && !accountIds.contains(accountId)) {
            throw new ApiException(403, "Access denied to account", "FORBIDDEN");
        }
        int take = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        StringBuilder sql = new StringBuilder("""
                SELECT t.*, a.institution_name, a.mask
                FROM transactions t JOIN accounts a ON a.id = t.account_id
                WHERE 
                """);
        List<Object> args = new ArrayList<>();
        if (accountId != null && !accountId.isBlank()) {
            sql.append("t.account_id = ?");
            args.add(accountId);
        } else {
            sql.append("t.account_id IN (").append("?,".repeat(accountIds.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");
            args.addAll(accountIds);
        }
        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND t.date >= ?");
            args.add(Time.format(Time.parseClient(startDate)));
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND t.date <= ?");
            args.add(Time.format(Time.parseClient(endDate)));
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND t.category = ?");
            args.add(category);
        }
        sql.append(" ORDER BY t.date DESC LIMIT ?");
        args.add(take);
        return jdbc.query(sql.toString(), (rs, n) -> transaction(rs, true), args.toArray());
    }

    public Map<String, Object> create(String userId, Map<String, Object> body) {
        String accountId = Body.text(body, "accountId");
        if (accountId == null) throw new ApiException(400, "Valid account ID required", "VALIDATION_ERROR");
        Ids.checkUuid(accountId);
        requireAccount(userId, accountId);
        Double amount = Body.decimal(body, "amount");
        if (amount == null) throw new ApiException(400, "Valid amount required", "VALIDATION_ERROR");
        String date = Body.text(body, "date");
        if (date == null || date.isBlank()) throw new ApiException(400, "Valid date required", "VALIDATION_ERROR");
        String name = limit(Body.text(body, "name"), 255, "name");
        String merchant = limit(Body.text(body, "merchantName"), 255, "merchantName");
        String category = Body.text(body, "category");
        String subcategory = Body.text(body, "subcategory");
        String notes = limit(Body.text(body, "notes"), 1000, "notes");
        if (category == null || category.isEmpty()) {
            Map<String, Object> guessed = CategorizationService.categorize(merchant != null ? merchant : name != null ? name : "Unknown", amount, notes);
            category = (String) guessed.get("category");
            subcategory = (String) guessed.get("subcategory");
        }
        String id = Ids.uuid();
        jdbc.update("""
                INSERT INTO transactions (id, account_id, amount, date, name, merchant_name, category, subcategory, notes, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, accountId, amount, Time.format(Time.parseClient(date)), name, merchant, category, subcategory, notes, Time.now());
        return jdbc.query("SELECT * FROM transactions WHERE id = ?", (rs, n) -> transaction(rs, false), id).get(0);
    }

    public Map<String, Object> patch(String userId, String id, Map<String, Object> body) {
        Ids.checkUuid(id);
        List<String> accountIds = accountIds(userId);
        if (accountIds.isEmpty()) throw new ApiException(404, "Transaction not found", "NOT_FOUND");
        StringBuilder sql = new StringBuilder("SELECT * FROM transactions WHERE id = ? AND account_id IN (");
        sql.append("?,".repeat(accountIds.size()));
        sql.setLength(sql.length() - 1);
        sql.append(")");
        List<Object> args = new ArrayList<>();
        args.add(id);
        args.addAll(accountIds);
        Map<String, Object> existing = jdbc.query(sql.toString(), (rs, n) -> transaction(rs, false), args.toArray())
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "Transaction not found", "NOT_FOUND"));
        List<String> sets = new ArrayList<>();
        List<Object> updates = new ArrayList<>();
        if (body.containsKey("category")) {
            sets.add("category = ?");
            updates.add(limit(Body.text(body, "category"), 100, "category"));
        }
        if (body.containsKey("subcategory")) {
            sets.add("subcategory = ?");
            updates.add(limit(Body.text(body, "subcategory"), 100, "subcategory"));
        }
        if (body.containsKey("notes")) {
            sets.add("notes = ?");
            updates.add(limit(Body.text(body, "notes"), 1000, "notes"));
        }
        if (!sets.isEmpty()) {
            updates.add(id);
            jdbc.update("UPDATE transactions SET " + String.join(", ", sets) + " WHERE id = ?", updates.toArray());
        }
        String category = Body.text(body, "category");
        if (body.containsKey("category") && category != null && !category.equals(existing.get("category"))) {
            try {
                String merchant = existing.get("merchantName") != null ? existing.get("merchantName").toString()
                        : existing.get("name") != null ? existing.get("name").toString() : "Unknown";
                categorization.recordFeedback(userId, id, merchant.trim(), ((Number) existing.get("amount")).doubleValue(),
                        (String) existing.get("notes"), (String) existing.get("category"), category, Body.text(body, "subcategory"));
            } catch (Exception ignored) {
                // Feedback is best-effort, matching the previous API.
            }
        }
        return jdbc.query("SELECT * FROM transactions WHERE id = ?", (rs, n) -> transaction(rs, false), id).get(0);
    }

    private void requireAccount(String userId, String accountId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE id = ? AND user_id = ?", Integer.class, accountId, userId);
        if (count == null || count == 0) throw new ApiException(404, "Account not found", "NOT_FOUND");
    }

    private List<String> accountIds(String userId) {
        return jdbc.query("SELECT id FROM accounts WHERE user_id = ?", (rs, n) -> rs.getString(1), userId);
    }

    private static String limit(String value, int max, String field) {
        if (value != null && value.length() > max) throw new ApiException(400, field + ": Invalid value", "VALIDATION_ERROR");
        return value;
    }

    private static Map<String, Object> transaction(ResultSet rs, boolean includeAccount) throws SQLException {
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("id", rs.getString("id"));
        tx.put("accountId", rs.getString("account_id"));
        tx.put("amount", rs.getDouble("amount"));
        tx.put("date", rs.getString("date"));
        tx.put("name", rs.getString("name"));
        tx.put("merchantName", rs.getString("merchant_name"));
        tx.put("category", rs.getString("category"));
        tx.put("subcategory", rs.getString("subcategory"));
        tx.put("notes", rs.getString("notes"));
        tx.put("createdAt", rs.getString("created_at"));
        if (includeAccount) {
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("institutionName", rs.getString("institution_name"));
            account.put("mask", rs.getString("mask"));
            tx.put("account", account);
        }
        return tx;
    }
}
