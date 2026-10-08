package com.verifi.web;

import com.verifi.security.Auth;
import com.verifi.service.AdminService;
import com.verifi.service.CategorizationService;
import com.verifi.service.CoachService;
import com.verifi.service.EligibilityService;
import com.verifi.service.InsightsService;
import com.verifi.service.KycService;
import com.verifi.service.LoanService;
import com.verifi.support.Body;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/loans")
class LoanController {
    private final LoanService loans;
    private final EligibilityService eligibility;

    LoanController(LoanService loans, EligibilityService eligibility) {
        this.loans = loans;
        this.eligibility = eligibility;
    }

    @GetMapping("/eligibility")
    Map<String, Object> eligibilityGet(@RequestParam(required = false) Double monthlyIncomeOverride, HttpServletRequest request) {
        return eligibility.score(Auth.require(request).userId(), monthlyIncomeOverride);
    }

    @PostMapping("/eligibility")
    Map<String, Object> eligibilityPost(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        return eligibility.score(Auth.require(request).userId(), Body.decimal(body, "monthlyIncomeOverride"));
    }

    @PostMapping("/apply")
    ResponseEntity<Map<String, Object>> apply(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ResponseEntity.status(201).body(loans.apply(Auth.require(request).userId(), body, Auth.clientIp(request)));
    }

    @GetMapping
    List<Map<String, Object>> list(HttpServletRequest request) { return loans.list(Auth.require(request).userId()); }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable String id, HttpServletRequest request) {
        return loans.get(Auth.require(request).userId(), id);
    }
}

@RestController
@RequestMapping("/api/insights")
class InsightsController {
    private final InsightsService insights;

    InsightsController(InsightsService insights) { this.insights = insights; }

    @GetMapping("/monthly")
    Map<String, Object> monthly(@RequestParam(required = false) Integer month, @RequestParam(required = false) Integer year, HttpServletRequest request) {
        return insights.monthly(Auth.require(request).userId(), month, year);
    }
}

@RestController
@RequestMapping("/api/kyc")
class KycController {
    private final KycService kyc;

    KycController(KycService kyc) { this.kyc = kyc; }

    @GetMapping("/me")
    Map<String, Object> me(HttpServletRequest request) { return kyc.me(Auth.require(request).userId()); }

    @PostMapping("/submit")
    ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ResponseEntity.status(201).body(kyc.submit(Auth.require(request).userId(), body));
    }

    @GetMapping("/admin")
    List<Map<String, Object>> admin(HttpServletRequest request) {
        Auth.requireAdmin(request);
        return kyc.adminList();
    }

    @PostMapping("/admin/{id}/review")
    Map<String, Object> review(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return kyc.review(Auth.requireAdmin(request).userId(), id, body);
    }
}

@RestController
@RequestMapping("/api/admin")
class AdminController {
    private final AdminService admin;

    AdminController(AdminService admin) { this.admin = admin; }

    @GetMapping("/users")
    List<Map<String, Object>> users(HttpServletRequest request) {
        Auth.requireAdmin(request);
        return admin.users();
    }

    @GetMapping("/loans")
    List<Map<String, Object>> loans(@RequestParam(required = false) String status, HttpServletRequest request) {
        Auth.requireAdmin(request);
        return admin.loans(status);
    }

    @PostMapping("/loans/{id}/status")
    Map<String, Object> status(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return admin.updateLoan(Auth.requireAdmin(request).userId(), id, Body.text(body, "action"), Auth.clientIp(request));
    }

    @GetMapping("/risk-scores")
    List<Map<String, Object>> risk(@RequestParam(required = false) Integer limit, HttpServletRequest request) {
        Auth.requireAdmin(request);
        return admin.riskScores(limit == null ? 100 : limit);
    }

    @GetMapping("/fraud-alerts")
    List<Map<String, Object>> fraud(@RequestParam(required = false) Integer limit, HttpServletRequest request) {
        Auth.requireAdmin(request);
        return admin.fraud(limit == null ? 50 : limit);
    }

    @GetMapping("/audit-logs")
    List<Map<String, Object>> audit(@RequestParam(required = false) Integer limit, HttpServletRequest request) {
        Auth.requireAdmin(request);
        return admin.auditLogs(limit == null ? 100 : limit);
    }
}

@RestController
@RequestMapping("/api/coach")
class CoachController {
    private final CoachService coach;

    CoachController(CoachService coach) { this.coach = coach; }

    @GetMapping("/history")
    Map<String, Object> history(HttpServletRequest request) { return coach.history(Auth.require(request).userId()); }

    @DeleteMapping("/history")
    ResponseEntity<Void> clear(HttpServletRequest request) {
        coach.clear(Auth.require(request).userId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/chat")
    @SuppressWarnings("unchecked")
    Map<String, Object> chat(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Object history = body.get("history");
        List<Map<String, Object>> items = history instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        return coach.chat(Auth.require(request).userId(), Body.text(body, "message"), items);
    }
}

@RestController
@RequestMapping("/api/categorize")
class CategorizeController {
    private final CategorizationService categorization;

    CategorizeController(CategorizationService categorization) { this.categorization = categorization; }

    @PostMapping
    Map<String, Object> categorize(@RequestBody Map<String, Object> body) {
        String merchant = Body.text(body, "merchantName");
        Double amount = Body.decimal(body, "amount");
        if (merchant == null || merchant.isEmpty()) {
            throw new com.verifi.error.ApiException(400, "merchantName required", "VALIDATION_ERROR");
        }
        if (amount == null) throw new com.verifi.error.ApiException(400, "Valid amount required", "VALIDATION_ERROR");
        String description = Body.text(body, "description");
        if (description != null && description.length() > 500) {
            throw new com.verifi.error.ApiException(400, "description: Invalid value", "VALIDATION_ERROR");
        }
        return CategorizationService.categorize(merchant, amount, description);
    }

    @GetMapping("/categories")
    Map<String, Object> categories() {
        return Map.of("categories", CategorizationService.CATEGORIES);
    }

    @PostMapping("/feedback")
    ResponseEntity<Map<String, Object>> feedback(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Double amount = Body.decimal(body, "amount");
        if (amount == null) throw new com.verifi.error.ApiException(400, "Valid amount required", "VALIDATION_ERROR");
        categorization.recordFeedback(Auth.require(request).userId(), Body.text(body, "transactionId"), Body.text(body, "merchantName"),
                amount, Body.text(body, "description"),
                Body.text(body, "originalCategory"), Body.text(body, "userCategory"), Body.text(body, "userSubcategory"));
        return ResponseEntity.status(201).body(Map.of("ok", true, "message", "Feedback recorded for ML training"));
    }

    @GetMapping("/training-data")
    Map<String, Object> training(@RequestParam(required = false) String userId, HttpServletRequest request) {
        var user = Auth.require(request);
        return categorization.trainingData(user.role(), user.userId(), userId);
    }
}
