package com.verifi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifi.config.AppProperties;
import com.verifi.error.ApiException;
import com.verifi.support.Body;
import com.verifi.support.Ids;
import com.verifi.support.Time;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AccountService {
    private static final List<Map<String, String>> INSTITUTIONS = List.of(
            Map.of("id", "ins_chase", "name", "Chase", "detail", "Checking & savings"),
            Map.of("id", "ins_boa", "name", "Bank of America", "detail", "Checking"),
            Map.of("id", "ins_wells", "name", "Wells Fargo", "detail", "Everyday checking"),
            Map.of("id", "ins_citi", "name", "Citi", "detail", "Citi Priority"),
            Map.of("id", "ins_capone", "name", "Capital One", "detail", "360 Checking")
    );
    private static final String[] MERCHANTS = {"Amazon", "Starbucks", "Walmart", "Netflix", "Spotify", "Shell", "Whole Foods", "Uber", "Target", "Costco"};
    private static final String[][] CATEGORIES = {
            {"Food and Drink", "Restaurants"},
            {"Travel", "Gas"},
            {"Entertainment", "Streaming"},
            {"Shopping", "Online"},
            {"Bills", "Utilities"},
            {"Food and Drink", "Groceries"}
    };

    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AccountService(JdbcTemplate jdbc, AppProperties props, ObjectMapper json) {
        this.jdbc = jdbc;
        this.props = props;
        this.json = json;
    }

    public Map<String, Object> linkConfig() {
        boolean plaid = props.plaidConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", plaid ? "plaid" : "demo");
        body.put("providerLabel", plaid ? "Plaid (" + props.getPlaidEnv() + ")" : "Demo sandbox");
        body.put("institutions", INSTITUTIONS);
        body.put("message", plaid
                ? "Plaid credentials detected. Link tokens use the Plaid sandbox API; completion still seeds local demo balances until live item exchange is fully wired."
                : "Running without Plaid keys — pick an institution to seed realistic sample balances and transactions. Set PLAID_CLIENT_ID and PLAID_SECRET to enable the Plaid path.");
        return body;
    }

    public Map<String, Object> linkToken(String userId) {
        if (!props.plaidConfigured()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", "demo");
            body.put("linkToken", "demo-link-" + userId.substring(0, Math.min(8, userId.length())) + "-" + Ids.uuid().substring(0, 8));
            body.put("expiration", Time.format(Instant.now().plus(30, ChronoUnit.MINUTES)));
            return body;
        }
        try {
            String payload = json.writeValueAsString(Map.of(
                    "client_id", props.getPlaidClientId(),
                    "secret", props.getPlaidSecret(),
                    "user", Map.of("client_user_id", userId),
                    "client_name", "Finance",
                    "products", List.of("transactions"),
                    "country_codes", List.of("US"),
                    "language", "en"
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create(plaidHost() + "/link/token/create"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String snippet = response.body() == null ? "" : response.body().substring(0, Math.min(200, response.body().length()));
                throw new ApiException(502, "Plaid link token failed: " + snippet, "PLAID_ERROR");
            }
            JsonNode node = json.readTree(response.body());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", "plaid");
            body.put("linkToken", node.path("link_token").asText());
            body.put("expiration", node.path("expiration").asText());
            return body;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(502, "Plaid link token failed: " + ex.getMessage(), "PLAID_ERROR");
        }
    }

    public List<Map<String, Object>> list(String userId) {
        return jdbc.query("""
                SELECT * FROM accounts WHERE user_id = ? AND is_active = 1 ORDER BY linked_at DESC
                """, (rs, n) -> account(rs), userId);
    }

    public Map<String, Object> get(String userId, String id) {
        Ids.checkUuid(id);
        return jdbc.query("SELECT * FROM accounts WHERE id = ? AND user_id = ?", (rs, n) -> account(rs), id, userId)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "Account not found", "NOT_FOUND"));
    }

    public Map<String, Object> create(String userId, Map<String, Object> body) {
        String institution = Body.text(body, "institutionName");
        if (institution == null || institution.isEmpty()) throw new ApiException(400, "Institution name required", "VALIDATION_ERROR");
        String mask = Body.text(body, "mask");
        if (mask != null && mask.length() > 10) throw new ApiException(400, "mask: Invalid value", "VALIDATION_ERROR");
        String type = Body.text(body, "accountType");
        String id = Ids.uuid();
        jdbc.update("""
                INSERT INTO accounts (id, user_id, institution_name, account_name, account_type, mask, current_balance, is_active, provider, external_item_id, link_status, linked_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, 1, 'demo', NULL, 'linked', ?)
                """, id, userId, institution, Body.text(body, "accountName"), type == null ? "checking" : type, mask, Time.now());
        return get(userId, id);
    }

    public Map<String, Object> link(String userId, Map<String, Object> body) {
        String institutionId = Body.text(body, "institutionId");
        String publicToken = Body.text(body, "publicToken");
        if (institutionId != null && institutionId.length() > 64) throw new ApiException(400, "institutionId: Invalid value", "VALIDATION_ERROR");
        if (publicToken != null && publicToken.length() > 256) throw new ApiException(400, "publicToken: Invalid value", "VALIDATION_ERROR");
        Map<String, String> institution = INSTITUTIONS.stream().filter(i -> i.get("id").equals(institutionId)).findFirst()
                .orElse(INSTITUTIONS.get(ThreadLocalRandom.current().nextInt(INSTITUTIONS.size())));
        String mode = props.plaidConfigured() ? "plaid" : "demo";
        String external = publicToken != null ? "item_" + publicToken.substring(0, Math.min(24, publicToken.length())) : "demo_item_" + Ids.uuid();
        double balance = Math.round((800 + ThreadLocalRandom.current().nextDouble() * 4200) * 100) / 100.0;
        String id = Ids.uuid();
        jdbc.update("""
                INSERT INTO accounts (id, user_id, institution_name, account_name, account_type, mask, current_balance, is_active, provider, external_item_id, link_status, linked_at)
                VALUES (?, ?, ?, ?, 'checking', ?, ?, 1, ?, ?, 'linked', ?)
                """, id, userId, institution.get("name"), institution.get("name") + " Checking",
                String.valueOf(ThreadLocalRandom.current().nextInt(1000, 10000)), balance, mode, external, Time.now());
        seedTransactions(id, 18, 75);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account", get(userId, id));
        result.put("provider", mode);
        result.put("institution", institution.get("name"));
        return result;
    }

    public Map<String, Object> sync(String userId, String id) {
        Ids.checkUuid(id);
        Map<String, Object> account = jdbc.query("SELECT * FROM accounts WHERE id = ? AND user_id = ?", (rs, n) -> account(rs), id, userId)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "Account not found", "NOT_FOUND"));
        seedTransactions(id, 5, 7);
        double bump = Math.round((ThreadLocalRandom.current().nextDouble() * 40 - 20) * 100) / 100.0;
        double next = Math.max(0, ((Number) account.get("currentBalance")).doubleValue() + bump);
        jdbc.update("UPDATE accounts SET current_balance = ? WHERE id = ?", next, id);
        return Map.of("synced", 5, "currentBalance", next);
    }

    private void seedTransactions(String accountId, int count, int daysBack) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            int daysAgo = random.nextInt(daysBack);
            String[] cat = CATEGORIES[random.nextInt(CATEGORIES.length)];
            String merchant = MERCHANTS[i % MERCHANTS.length];
            double amount = -(random.nextInt(15000) + 500) / 100.0;
            String date = Time.format(Instant.now().minus(daysAgo, ChronoUnit.DAYS));
            jdbc.update("""
                    INSERT INTO transactions (id, account_id, amount, date, name, merchant_name, category, subcategory, notes, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                    """, Ids.uuid(), accountId, amount, date, merchant, merchant, cat[0], cat[1], Time.now());
        }
    }

    private String plaidHost() {
        if ("production".equals(props.getPlaidEnv())) return "https://production.plaid.com";
        if ("development".equals(props.getPlaidEnv())) return "https://development.plaid.com";
        return "https://sandbox.plaid.com";
    }

    static Map<String, Object> account(ResultSet rs) throws SQLException {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", rs.getString("id"));
        account.put("userId", rs.getString("user_id"));
        account.put("institutionName", rs.getString("institution_name"));
        account.put("accountName", rs.getString("account_name"));
        account.put("accountType", rs.getString("account_type"));
        account.put("mask", rs.getString("mask"));
        Double balance = Body.dbl(rs, "current_balance");
        account.put("currentBalance", balance == null ? 0d : balance);
        account.put("isActive", Body.bool(rs, "is_active"));
        account.put("provider", rs.getString("provider"));
        account.put("externalItemId", rs.getString("external_item_id"));
        account.put("linkStatus", rs.getString("link_status"));
        account.put("linkedAt", rs.getString("linked_at"));
        return account;
    }
}
