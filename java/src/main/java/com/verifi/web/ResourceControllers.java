package com.verifi.web;

import com.verifi.security.Auth;
import com.verifi.service.AccountService;
import com.verifi.service.TransactionService;
import com.verifi.service.BudgetService;
import com.verifi.service.SavingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
class AccountController {
    private final AccountService accounts;

    AccountController(AccountService accounts) { this.accounts = accounts; }

    @GetMapping("/link/config")
    Map<String, Object> config() { return accounts.linkConfig(); }

    @PostMapping("/link/token")
    Map<String, Object> token(HttpServletRequest request) { return accounts.linkToken(Auth.require(request).userId()); }

    @GetMapping
    List<Map<String, Object>> list(HttpServletRequest request) { return accounts.list(Auth.require(request).userId()); }

    @PostMapping("/link")
    ResponseEntity<Map<String, Object>> link(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        return ResponseEntity.status(201).body(accounts.link(Auth.require(request).userId(), body == null ? Map.of() : body));
    }

    @PostMapping("/{id}/sync")
    Map<String, Object> sync(@PathVariable String id, HttpServletRequest request) {
        return accounts.sync(Auth.require(request).userId(), id);
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ResponseEntity.status(201).body(accounts.create(Auth.require(request).userId(), body));
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable String id, HttpServletRequest request) {
        return accounts.get(Auth.require(request).userId(), id);
    }
}

@RestController
@RequestMapping("/api/transactions")
class TransactionController {
    private final TransactionService transactions;

    TransactionController(TransactionService transactions) { this.transactions = transactions; }

    @GetMapping("/categories")
    List<String> categories(HttpServletRequest request) { return transactions.categories(Auth.require(request).userId()); }

    @GetMapping
    List<Map<String, Object>> list(HttpServletRequest request,
                                   @RequestParam(required = false) String accountId,
                                   @RequestParam(required = false) String startDate,
                                   @RequestParam(required = false) String endDate,
                                   @RequestParam(required = false) String category,
                                   @RequestParam(required = false) Integer limit) {
        return transactions.list(Auth.require(request).userId(), accountId, startDate, endDate, category, limit);
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ResponseEntity.status(201).body(transactions.create(Auth.require(request).userId(), body));
    }

    @PatchMapping("/{id}")
    Map<String, Object> patch(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return transactions.patch(Auth.require(request).userId(), id, body);
    }
}

@RestController
@RequestMapping("/api/budgets")
class BudgetController {
    private final BudgetService budgets;

    BudgetController(BudgetService budgets) { this.budgets = budgets; }

    @GetMapping
    List<Map<String, Object>> list(HttpServletRequest request) { return budgets.list(Auth.require(request).userId()); }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ResponseEntity.status(201).body(budgets.create(Auth.require(request).userId(), body));
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable String id, HttpServletRequest request) {
        return budgets.get(Auth.require(request).userId(), id);
    }

    @PatchMapping("/{id}")
    Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return budgets.update(Auth.require(request).userId(), id, body);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) {
        budgets.delete(Auth.require(request).userId(), id);
        return ResponseEntity.noContent().build();
    }
}

@RestController
@RequestMapping("/api/savings")
class SavingsController {
    private final SavingsService savings;

    SavingsController(SavingsService savings) { this.savings = savings; }

    @GetMapping
    List<Map<String, Object>> list(HttpServletRequest request) { return savings.list(Auth.require(request).userId()); }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return ResponseEntity.status(201).body(savings.create(Auth.require(request).userId(), body));
    }

    @PatchMapping("/{id}")
    Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return savings.update(Auth.require(request).userId(), id, body);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) {
        savings.delete(Auth.require(request).userId(), id);
        return ResponseEntity.noContent().build();
    }
}
