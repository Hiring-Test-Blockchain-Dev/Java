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
import java.util.Locale;
import java.util.Map;

@Service
public class CategorizationService {
    public static final List<String> CATEGORIES = List.of(
            "Food", "Rent", "Transport", "Shopping", "Entertainment", "Utilities", "Healthcare", "Income",
            "Transfer", "Personal Care", "Education", "Subscriptions", "Insurance", "Fees & Charges", "Other");
    private static final List<Rule> RULES = rules();
    private final JdbcTemplate jdbc;

    public CategorizationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static Map<String, Object> categorize(String merchantName, double amount, String description) {
        Map<String, Object> features = features(merchantName, amount, description);
        String text = (String) features.get("combinedText");
        if (text == null || text.length() < 2) return result("Other", null, 0.2, features, null);
        for (Rule rule : RULES) {
            if (rule.amountRange != null && Boolean.TRUE.equals(features.get("isCredit"))) {
                double abs = (Double) features.get("amountAbs");
                if (abs < rule.amountRange[0] || abs > rule.amountRange[1]) continue;
            }
            String matched = null;
            for (String keyword : rule.keywords) {
                if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                    matched = keyword;
                    break;
                }
            }
            if (matched != null) return result(rule.category, rule.subcategory, rule.confidence, features, matched);
        }
        if (Boolean.TRUE.equals(features.get("isCredit")) && (Double) features.get("amountAbs") > 100) {
            return result("Income", null, 0.5, features, null);
        }
        return result("Other", null, 0.3, features, null);
    }

    public void recordFeedback(String userId, String transactionId, String merchant, double amount, String description,
                               String original, String userCategory, String userSubcategory) {
        if (userCategory == null || userCategory.isBlank()) throw new ApiException(400, "userCategory required", "VALIDATION_ERROR");
        if (merchant == null || merchant.isBlank()) throw new ApiException(400, "merchantName required", "VALIDATION_ERROR");
        if (transactionId != null) Ids.checkUuid(transactionId);
        jdbc.update("""
                INSERT INTO categorization_feedback
                (id, user_id, transaction_id, merchant_name, amount, description, original_category, user_category, user_subcategory, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Ids.uuid(), userId, transactionId, merchant, amount, description, original, userCategory, userSubcategory, Time.now());
    }

    public Map<String, Object> trainingData(String role, String selfId, String requestedUserId) {
        boolean admin = "admin".equals(role);
        String filter = admin ? (requestedUserId == null || requestedUserId.isBlank() ? null : requestedUserId) : selfId;
        if (filter != null) Ids.checkUuid(filter);
        List<Map<String, Object>> rows = filter == null
                ? jdbc.query("SELECT merchant_name, amount, description, user_category, user_subcategory FROM categorization_feedback ORDER BY created_at DESC LIMIT 10000", (rs, n) -> feedback(rs))
                : jdbc.query("SELECT merchant_name, amount, description, user_category, user_subcategory FROM categorization_feedback WHERE user_id = ? ORDER BY created_at DESC LIMIT 10000", (rs, n) -> feedback(rs), filter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", rows.size());
        body.put("records", rows);
        return body;
    }

    private static Map<String, Object> feedback(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("merchantName", rs.getString("merchant_name"));
        row.put("amount", numberString(rs.getDouble("amount")));
        row.put("description", rs.getString("description"));
        row.put("label", rs.getString("user_category"));
        row.put("sublabel", rs.getString("user_subcategory"));
        return row;
    }

    private static String numberString(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) return String.valueOf((long) value);
        return String.valueOf(value);
    }

    private static Map<String, Object> features(String merchantName, double amount, String description) {
        String merchant = merchantName == null ? "" : merchantName.trim();
        String desc = description == null ? "" : description.trim();
        String combined = (merchant + " " + desc).trim().toLowerCase(Locale.ROOT);
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("merchantName", merchant);
        features.put("merchantNameLower", merchant.toLowerCase(Locale.ROOT));
        features.put("merchantTokens", tokenize(merchant));
        features.put("description", desc);
        features.put("descriptionTokens", tokenize(desc));
        features.put("amount", amount);
        features.put("amountAbs", Math.abs(amount));
        features.put("amountBucket", bucket(amount));
        features.put("isCredit", amount > 0);
        features.put("combinedText", combined);
        return features;
    }

    private static List<String> tokenize(String text) {
        String[] parts = text.toLowerCase(Locale.ROOT).replaceAll("[^\\w\\s]", " ").split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) if (part.length() > 1) tokens.add(part);
        return tokens;
    }

    private static String bucket(double amount) {
        double abs = Math.abs(amount);
        if (abs < 10) return "0-10";
        if (abs < 50) return "10-50";
        if (abs < 100) return "50-100";
        if (abs < 500) return "100-500";
        return "500+";
    }

    private static Map<String, Object> result(String category, String subcategory, double confidence, Map<String, Object> features, String matched) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", category);
        body.put("subcategory", subcategory);
        body.put("confidence", confidence);
        body.put("method", "rule");
        body.put("features", features);
        if (matched != null) body.put("matchedRule", matched);
        return body;
    }

    private static List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        rules.add(rule("Food", "Restaurants", 0.95, "restaurant", "cafe", "diner", "bistro", "grill", "pizzeria", "sushi", "mcdonald", "burger", "wendy", "taco", "chipotle", "panera"));
        rules.add(rule("Food", "Coffee", 0.95, "starbucks", "dunkin", "coffee", "espresso", "costa"));
        rules.add(rule("Food", "Groceries", 0.9, "whole foods", "trader joe", "safeway", "kroger", "albertsons", "publix", "costco", "walmart", "target", "grocery", "supermarket"));
        rules.add(rule("Food", "Fast Food", 0.92, "mcdonald", "burger king", "wendy", "kfc", "subway", "domino", "papa john", "pizza hut"));
        rules.add(rule("Food", "Alcohol", 0.9, "liquor", "wine", "beer", "bar ", "pub ", "tavern"));
        rules.add(rule("Rent", null, 0.95, "rent", "lease", "landlord", "apartment", "housing"));
        rules.add(rule("Rent", null, 0.85, "zillow", "apartments.com", "redfin"));
        rules.add(rule("Transport", "Fuel", 0.95, "shell", "chevron", "exxon", "bp ", "gas station", "fuel", "esso", "mobil"));
        rules.add(rule("Transport", "Rideshare", 0.98, "uber", "lyft", "grab"));
        rules.add(rule("Transport", "Transit", 0.9, "metro", "bus ", "train", "transit", "mta", "barta", "tfl"));
        rules.add(rule("Transport", "Parking", 0.9, "parking", "parkmobile", "spothero"));
        rules.add(rule("Transport", "Tolls", 0.95, "toll", "ezpass", "fastrak", "sunpass"));
        rules.add(rule("Shopping", "Online", 0.9, "amazon", "ebay", "etsy", "aliexpress"));
        rules.add(rule("Shopping", "Clothing", 0.9, "nike", "adidas", "zara", "h&m", "gap", "old navy", "nordstrom", "macys"));
        rules.add(rule("Shopping", "Electronics", 0.92, "apple ", "best buy", "newegg", "microcenter"));
        rules.add(rule("Shopping", "General", 0.75, "target", "walmart", "costco", "home depot", "lowes", "ikea"));
        rules.add(rule("Entertainment", "Streaming", 0.98, "netflix", "spotify", "disney", "hulu", "hbo", "paramount", "youtube", "prime video"));
        rules.add(rule("Entertainment", "Movies", 0.92, "amc", "regal", "cinemark", "movie", "theater"));
        rules.add(rule("Entertainment", "Events", 0.88, "ticketmaster", "eventbrite", "stubhub", "concert", "show"));
        rules.add(rule("Entertainment", "Games", 0.95, "steam", "playstation", "xbox", "nintendo", "epic games"));
        rules.add(rule("Utilities", "Electric", 0.95, "electric", "electricity", "pge", "coned", "duke energy"));
        rules.add(rule("Utilities", "Gas", 0.9, "gas utility", "natural gas", "gas bill"));
        rules.add(rule("Utilities", "Water", 0.95, "water bill", "water utility"));
        rules.add(rule("Utilities", "Internet", 0.9, "comcast", "verizon fios", "at&t", "spectrum", "xfinity", "internet"));
        rules.add(rule("Utilities", "Phone", 0.9, "verizon", "at&t", "t-mobile", "sprint", "mobile bill", "phone bill"));
        rules.add(rule("Healthcare", "Pharmacy", 0.9, "cvs", "walgreens", "rite aid", "pharmacy"));
        rules.add(rule("Healthcare", "Doctor", 0.85, "doctor", "medical", "hospital", "clinic", "physician"));
        rules.add(rule("Healthcare", "Dental", 0.95, "dental", "dentist"));
        rules.add(rule("Healthcare", "Insurance", 0.9, "health insurance", "medical insurance"));
        rules.add(rule("Subscriptions", null, 0.7, "subscription", "monthly", "annual plan", "membership"));
        rules.add(rule("Insurance", null, 0.92, "insurance", "geico", "state farm", "allstate", "progressive", "liberty mutual"));
        rules.add(rule("Fees & Charges", null, 0.85, "fee", "charge", "overdraft", "atm", "service charge"));
        rules.add(rule("Fees & Charges", "ATM", 0.95, "atm fee", "atm withdrawal"));
        rules.add(new Rule("Income", null, 0.85, new double[]{0, 100000}, "payroll", "salary", "direct dep", "direct deposit", "deposit", "income"));
        rules.add(rule("Personal Care", null, 0.88, "gym", "fitness", "yoga", "spa", "salon", "barber", "haircut"));
        rules.add(rule("Education", null, 0.9, "university", "college", "school", "tuition", "coursera", "udemy", "edx"));
        rules.add(rule("Transfer", null, 0.75, "transfer", "zelle", "venmo", "paypal", "cash app"));
        return rules;
    }

    private static Rule rule(String category, String subcategory, double confidence, String... keywords) {
        return new Rule(category, subcategory, confidence, null, keywords);
    }

    private record Rule(String category, String subcategory, double confidence, double[] amountRange, String... keywords) {}
}
