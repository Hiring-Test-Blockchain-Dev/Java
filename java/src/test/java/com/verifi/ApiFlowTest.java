package com.verifi;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiFlowTest {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("verifi.datasource-url", () -> "jdbc:sqlite:target/verifi-" + UUID.randomUUID() + ".db?foreign_keys=on&busy_timeout=5000");
        registry.add("verifi.openai-api-key", () -> "");
        registry.add("verifi.kyc-auto-approve", () -> "true");
        registry.add("verifi.production", () -> "false");
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void demoLoginAccountsKycAndLoan() {
        ResponseEntity<JsonNode> health = rest.getForEntity("/health", JsonNode.class);
        assertEquals(200, health.getStatusCode().value());
        assertEquals("ok", health.getBody().get("status").asText());

        ResponseEntity<JsonNode> locked = rest.getForEntity("/api/accounts", JsonNode.class);
        assertEquals(401, locked.getStatusCode().value());

        ResponseEntity<JsonNode> demo = rest.postForEntity("/api/auth/demo", null, JsonNode.class);
        assertEquals(200, demo.getStatusCode().value());
        String token = demo.getBody().get("accessToken").asText();
        assertEquals("user", demo.getBody().get("user").get("role").asText());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> accounts = rest.exchange("/api/accounts", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertEquals(200, accounts.getStatusCode().value());
        assertTrue(accounts.getBody().isArray());
        assertEquals("Demo Bank", accounts.getBody().get(0).get("institutionName").asText());

        ResponseEntity<JsonNode> category = rest.postForEntity("/api/categorize",
                Map.of("merchantName", "Starbucks", "amount", -4.5), JsonNode.class);
        assertEquals(200, category.getStatusCode().value());
        assertEquals("Food", category.getBody().get("category").asText());
        assertEquals("Coffee", category.getBody().get("subcategory").asText());

        ResponseEntity<JsonNode> denied = rest.exchange("/api/loans/apply", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 1000, "termMonths", 12), headers), JsonNode.class);
        assertEquals(403, denied.getStatusCode().value());

        ResponseEntity<JsonNode> kyc = rest.exchange("/api/kyc/submit", HttpMethod.POST, new HttpEntity<>(Map.of(
                "fullName", "Demo User",
                "dateOfBirth", "1990-01-15",
                "country", "United States",
                "documentType", "drivers_license",
                "documentLast4", "1234"
        ), headers), JsonNode.class);
        assertEquals(201, kyc.getStatusCode().value());
        assertEquals("verified", kyc.getBody().get("status").asText());

        ResponseEntity<JsonNode> loan = rest.exchange("/api/loans/apply", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 1000, "termMonths", 12), headers), JsonNode.class);
        assertEquals(201, loan.getStatusCode().value());
        assertEquals("pending", loan.getBody().get("status").asText());

        ResponseEntity<JsonNode> coach = rest.exchange("/api/coach/chat", HttpMethod.POST,
                new HttpEntity<>(Map.of("message", "What is my balance?"), headers), JsonNode.class);
        assertEquals(200, coach.getStatusCode().value());
        assertTrue(coach.getBody().get("message").asText().toLowerCase().contains("balance"));
    }
}
