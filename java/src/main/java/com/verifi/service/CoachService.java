package com.verifi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifi.config.AppProperties;
import com.verifi.error.ApiException;
import com.verifi.support.Ids;
import com.verifi.support.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class CoachService {
    private static final Logger log = LoggerFactory.getLogger(CoachService.class);
    private static final String DISCLAIMER = "\n\n---\n*This is educational information only, not financial advice. Consult a licensed advisor for decisions about your money.*";
    private static final String DISCLAIMER_FIELD = "This is educational information only, not financial advice. Consult a licensed advisor for decisions about your money.";
    private static final Pattern BALANCE = Pattern.compile("balance|how much.*(have|left)|net worth", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUDGET = Pattern.compile("budget", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPEND = Pattern.compile("spend|spending|cut|save more|where.*money", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOAN = Pattern.compile("loan|eligible|credit|risk", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public CoachService(JdbcTemplate jdbc, AppProperties props, ObjectMapper json) {
        this.jdbc = jdbc;
        this.props = props;
        this.json = json;
    }

    public Map<String, Object> history(String userId) {
        List<Map<String, Object>> messages = jdbc.query("""
                SELECT id, role, content, created_at FROM coach_messages
                WHERE user_id = ? ORDER BY created_at ASC LIMIT 100
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getString("id"));
            row.put("role", rs.getString("role"));
            row.put("content", rs.getString("content"));
            row.put("createdAt", rs.getString("created_at"));
            return row;
        }, userId);
        return Map.of("messages", messages);
    }

    public void clear(String userId) {
        jdbc.update("DELETE FROM coach_messages WHERE user_id = ?", userId);
    }

    public Map<String, Object> chat(String userId, String message, List<Map<String, Object>> history) {
        if (message == null || message.isBlank()) throw new ApiException(400, "Message is required", "VALIDATION_ERROR");
        if (message.length() > 2000) throw new ApiException(400, "Message must be 2000 characters or less", "VALIDATION_ERROR");
        if (history != null) {
            for (Map<String, Object> item : history) {
                Object role = item.get("role");
                Object content = item.get("content");
                if (role != null) {
                    String roleText = role.toString();
                    if (!roleText.equals("user") && !roleText.equals("assistant") && !roleText.equals("system")) {
                        throw new ApiException(400, "Invalid role in history", "VALIDATION_ERROR");
                    }
                }
                if (content != null && content.toString().length() > 2000) {
                    throw new ApiException(400, "Invalid content in history", "VALIDATION_ERROR");
                }
            }
        }
        jdbc.update("INSERT INTO coach_messages (id, user_id, role, content, created_at) VALUES (?, ?, 'user', ?, ?)",
                Ids.uuid(), userId, message, Time.now());
        Map<String, Object> result = props.openAiEnabled() ? openAiOrLocal(userId, message, history) : local(userId, message);
        jdbc.update("INSERT INTO coach_messages (id, user_id, role, content, created_at) VALUES (?, ?, 'assistant', ?, ?)",
                Ids.uuid(), userId, result.get("message"), Time.now());
        return result;
    }

    private Map<String, Object> openAiOrLocal(String userId, String message, List<Map<String, Object>> history) {
        try {
            return openAi(userId, message, history);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("OpenAI coach failed, falling back to local coach", ex);
            Map<String, Object> local = local(userId, message);
            local.put("message", "Cloud AI is temporarily unavailable, so here is a local summary based on your account data:\n\n" + local.get("message"));
            return local;
        }
    }

    private Map<String, Object> openAi(String userId, String message, List<Map<String, Object>> history) throws Exception {
        Snapshot snap = snapshot(userId);
        String system = """
                You are a helpful AI financial coach within this app. Your role is to provide general guidance only.

                ## Rules (strict)
                1. NEVER give specific investment recommendations, buy/sell advice, or tax advice.
                2. NEVER guarantee outcomes or returns.
                3. Base advice on the user's anonymized data (budgets, spending by category) when provided.
                4. Be explainable: briefly state why you suggest something.
                5. Keep answers concise (2–4 short paragraphs). Use bullet points when listing steps.
                6. If you lack context, ask the user to add accounts/budgets or clarify their situation.
                7. Always stay supportive and non-judgmental.
                8. Do NOT output the disclaimer yourself—it is appended automatically.

                ## User's financial context (anonymized)
                """ + context(snap);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        if (history != null) {
            int from = Math.max(0, history.size() - 8);
            for (int i = from; i < history.size(); i++) {
                Object role = history.get(i).get("role");
                Object content = history.get(i).get("content");
                if (content == null) continue;
                String roleText = role == null ? "" : role.toString();
                if (!roleText.equals("user") && !roleText.equals("assistant") && !roleText.equals("system")) {
                    throw new ApiException(400, "Invalid role in history", "VALIDATION_ERROR");
                }
                if (content.toString().length() > 2000) throw new ApiException(400, "Invalid content in history", "VALIDATION_ERROR");
                messages.add(Map.of("role", roleText, "content", content.toString()));
            }
        }
        messages.add(Map.of("role", "user", "content", message));
        String payload = json.writeValueAsString(Map.of(
                "model", "gpt-4o-mini",
                "messages", messages,
                "max_tokens", 600,
                "temperature", 0.5
        ));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + props.getOpenaiApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OpenAI status " + response.statusCode());
        }
        JsonNode content = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
        String text = content.isMissingNode() || content.asText().isBlank()
                ? "I could not generate a response. Please try rephrasing your question."
                : content.asText().trim();
        List<String> sources = new ArrayList<>();
        if (!snap.budgets.isEmpty()) sources.add("budgets");
        if (!snap.monthly.isEmpty()) sources.add("transaction history");
        return reply(text + DISCLAIMER, sources);
    }

    private Map<String, Object> local(String userId, String message) {
        Snapshot snap = snapshot(userId);
        double totalSpent = snap.monthly.stream().mapToDouble(row -> ((Number) row.get("amount")).doubleValue()).sum();
        List<String> lines = new ArrayList<>();
        if (BALANCE.matcher(message).find()) {
            lines.add("Across your linked accounts, your approximate balance is **$" + money(snap.balance) + "** (" + snap.accounts + " account" + (snap.accounts == 1 ? "" : "s") + ").");
        } else if (BUDGET.matcher(message).find()) {
            if (snap.budgets.isEmpty()) lines.add("You do not have budgets set yet. Add one under Budgets to track monthly limits.");
            else {
                lines.add("Here is a quick budget snapshot:");
                for (Map<String, Object> budget : snap.budgets.stream().limit(5).toList()) {
                    String category = String.valueOf(budget.get("category")).toLowerCase(Locale.ROOT);
                    double spent = snap.monthly.stream()
                            .filter(tx -> String.valueOf(tx.get("category")).toLowerCase(Locale.ROOT).contains(category))
                            .mapToDouble(tx -> ((Number) tx.get("amount")).doubleValue()).sum();
                    lines.add("- **" + budget.get("name") + "** (" + budget.get("category") + "): $" + money(spent) + " of $" + money(((Number) budget.get("limit")).doubleValue()));
                }
            }
        } else if (SPEND.matcher(message).find()) {
            lines.add("This month you have spent about **$" + money(totalSpent) + "**.");
            if (snap.byCategory.isEmpty()) lines.add("Link accounts and sync transactions so I can break spending down by category.");
            else {
                lines.add("Top categories:");
                for (Map<String, Object> row : snap.byCategory.stream().limit(4).toList()) {
                    lines.add("- " + row.get("category") + ": $" + money(((Number) row.get("amount")).doubleValue()));
                }
                lines.add("Consider setting or tightening a budget on your largest category first.");
            }
        } else if (LOAN.matcher(message).find()) {
            lines.add("Loan eligibility uses your cash-flow and spending patterns. Keep accounts linked, stay within budgets, and complete identity verification under Verify before applying.");
        } else {
            lines.add("I am running in **local coach mode** (no cloud AI key). Based on your data: balance ≈ **$" + money(snap.balance) + "**, spent this month ≈ **$" + money(totalSpent) + "**.");
            if (!snap.byCategory.isEmpty()) {
                Map<String, Object> top = snap.byCategory.get(0);
                lines.add("Your largest category right now is **" + top.get("category") + "** ($" + money(((Number) top.get("amount")).doubleValue()) + ").");
            }
            lines.add("Ask about balance, budgets, spending, or loans for a more specific summary.");
        }
        List<String> sources = new ArrayList<>();
        sources.add("local_coach");
        if (snap.accounts > 0) sources.add("accounts");
        if (!snap.monthly.isEmpty()) sources.add("transactions");
        return reply(String.join("\n\n", lines) + DISCLAIMER, sources);
    }

    private Snapshot snapshot(String userId) {
        ZoneId zone = ZoneId.systemDefault();
        String monthStart = Time.format(LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant());
        String now = Time.now();
        Snapshot snap = new Snapshot();
        Double balance = jdbc.queryForObject("SELECT COALESCE(SUM(current_balance), 0) FROM accounts WHERE user_id = ? AND is_active = 1", Double.class, userId);
        Integer accounts = jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE user_id = ? AND is_active = 1", Integer.class, userId);
        snap.balance = balance == null ? 0 : balance;
        snap.accounts = accounts == null ? 0 : accounts;
        snap.budgets = jdbc.query("""
                SELECT name, category, amount FROM budgets
                WHERE user_id = ? AND start_date <= ? AND (end_date IS NULL OR end_date >= ?)
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", rs.getString("name"));
            row.put("category", rs.getString("category"));
            row.put("limit", rs.getDouble("amount"));
            return row;
        }, userId, now, now);
        snap.monthly = jdbc.query("""
                SELECT t.category, ABS(t.amount) AS amount FROM transactions t
                JOIN accounts a ON a.id = t.account_id
                WHERE a.user_id = ? AND t.date >= ? AND t.amount < 0
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", rs.getString("category") == null ? "Uncategorized" : rs.getString("category"));
            row.put("amount", rs.getDouble("amount"));
            return row;
        }, userId, monthStart);
        Map<String, Double> totals = new LinkedHashMap<>();
        for (Map<String, Object> row : snap.monthly) totals.merge((String) row.get("category"), ((Number) row.get("amount")).doubleValue(), Double::sum);
        snap.byCategory = totals.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("category", entry.getKey());
                    row.put("amount", entry.getValue());
                    return row;
                }).toList();
        return snap;
    }

    private static String context(Snapshot snap) {
        StringBuilder text = new StringBuilder();
        if (snap.budgets.isEmpty()) text.append("User has no budgets set.\n");
        else {
            text.append("User budgets (this month):\n");
            for (Map<String, Object> budget : snap.budgets) {
                text.append("- ").append(budget.get("category")).append(": $").append(money(((Number) budget.get("limit")).doubleValue())).append(" limit\n");
            }
        }
        text.append("\nTotal account balance (approx): $").append(money(snap.balance));
        return text.toString();
    }

    private static Map<String, Object> reply(String message, List<String> sources) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("disclaimer", DISCLAIMER_FIELD);
        body.put("sourcesUsed", sources);
        return body;
    }

    private static String money(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static final class Snapshot {
        double balance;
        int accounts;
        List<Map<String, Object>> budgets = List.of();
        List<Map<String, Object>> monthly = List.of();
        List<Map<String, Object>> byCategory = List.of();
    }
}
