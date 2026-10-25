package com.verifi.service;

import com.verifi.support.Ids;
import com.verifi.support.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSeeder implements ApplicationRunner {
    public static final String DEMO_WALLET = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";
    public static final String ADMIN_WALLET = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
    private static final Logger log = LoggerFactory.getLogger(DatabaseSeeder.class);
    private final JdbcTemplate jdbc;

    public DatabaseSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (count != null && count > 0) return;
        String now = Time.now();
        String adminId = Ids.uuid();
        String userId = Ids.uuid();
        jdbc.update("""
                INSERT INTO users (id, wallet_address, email, password_hash, first_name, last_name, role, is_active, kyc_status, created_at, updated_at)
                VALUES (?, ?, NULL, NULL, ?, ?, ?, 1, 'unverified', ?, ?)
                """, adminId, ADMIN_WALLET, "Admin", "User", "admin", now, now);
        jdbc.update("""
                INSERT INTO users (id, wallet_address, email, password_hash, first_name, last_name, role, is_active, kyc_status, created_at, updated_at)
                VALUES (?, ?, NULL, NULL, ?, ?, ?, 1, 'unverified', ?, ?)
                """, userId, DEMO_WALLET, "Demo", "User", "user", now, now);
        jdbc.update("""
                INSERT INTO accounts (id, user_id, institution_name, account_name, account_type, mask, current_balance, is_active, provider, external_item_id, link_status, linked_at)
                VALUES (?, ?, 'Demo Bank', 'Checking', 'checking', '4242', 5000, 1, 'demo', NULL, 'linked', ?)
                """, Ids.uuid(), userId, now);
        log.info("Seeded wallet users: {} {}", ADMIN_WALLET, DEMO_WALLET);
    }
}
