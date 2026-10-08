package com.verifi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "verifi")
public class AppProperties {
    private String jwtSecret = "dev-secret-change-in-production";
    private String jwtExpiresIn = "15m";
    private String corsOrigin = "http://localhost:5173";
    private boolean production;
    private int apiRateLimitMax = 200;
    private int authRateLimitMax = 30;
    private int nonceRateLimitMax = 40;
    private int coachRateLimitMax = 40;
    private String openaiApiKey = "";
    private String plaidClientId = "";
    private String plaidSecret = "";
    private String plaidEnv = "sandbox";
    private boolean kycAutoApprove = true;
    private String datasourceUrl = "";

    public long expirySeconds() {
        String raw = jwtExpiresIn == null || jwtExpiresIn.isBlank() ? "15m" : jwtExpiresIn.trim();
        try {
            if (raw.endsWith("ms")) return Math.max(1, Long.parseLong(raw.substring(0, raw.length() - 2)) / 1000);
            if (raw.endsWith("m")) return Long.parseLong(raw.substring(0, raw.length() - 1)) * 60;
            if (raw.endsWith("h")) return Long.parseLong(raw.substring(0, raw.length() - 1)) * 3600;
            if (raw.endsWith("d")) return Long.parseLong(raw.substring(0, raw.length() - 1)) * 86400;
            if (raw.endsWith("s")) return Long.parseLong(raw.substring(0, raw.length() - 1));
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            return 900;
        }
    }

    public boolean plaidConfigured() {
        return notBlank(plaidClientId) && notBlank(plaidSecret);
    }

    public boolean openAiEnabled() {
        return openaiApiKey != null && openaiApiKey.length() >= 10 && !openaiApiKey.contains("your-openai-api-key");
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public String getJwtExpiresIn() { return jwtExpiresIn; }
    public void setJwtExpiresIn(String jwtExpiresIn) { this.jwtExpiresIn = jwtExpiresIn; }
    public String getCorsOrigin() { return corsOrigin; }
    public void setCorsOrigin(String corsOrigin) { this.corsOrigin = corsOrigin; }
    public boolean isProduction() { return production; }
    public void setProduction(boolean production) { this.production = production; }
    public int getApiRateLimitMax() { return apiRateLimitMax; }
    public void setApiRateLimitMax(int apiRateLimitMax) { this.apiRateLimitMax = apiRateLimitMax; }
    public int getAuthRateLimitMax() { return authRateLimitMax; }
    public void setAuthRateLimitMax(int authRateLimitMax) { this.authRateLimitMax = authRateLimitMax; }
    public int getNonceRateLimitMax() { return nonceRateLimitMax; }
    public void setNonceRateLimitMax(int nonceRateLimitMax) { this.nonceRateLimitMax = nonceRateLimitMax; }
    public int getCoachRateLimitMax() { return coachRateLimitMax; }
    public void setCoachRateLimitMax(int coachRateLimitMax) { this.coachRateLimitMax = coachRateLimitMax; }
    public String getOpenaiApiKey() { return openaiApiKey; }
    public void setOpenaiApiKey(String openaiApiKey) { this.openaiApiKey = openaiApiKey; }
    public String getPlaidClientId() { return plaidClientId; }
    public void setPlaidClientId(String plaidClientId) { this.plaidClientId = plaidClientId; }
    public String getPlaidSecret() { return plaidSecret; }
    public void setPlaidSecret(String plaidSecret) { this.plaidSecret = plaidSecret; }
    public String getPlaidEnv() { return plaidEnv; }
    public void setPlaidEnv(String plaidEnv) { this.plaidEnv = plaidEnv; }
    public boolean isKycAutoApprove() { return kycAutoApprove; }
    public void setKycAutoApprove(boolean kycAutoApprove) { this.kycAutoApprove = kycAutoApprove; }
    public String getDatasourceUrl() { return datasourceUrl; }
    public void setDatasourceUrl(String datasourceUrl) { this.datasourceUrl = datasourceUrl; }
}
