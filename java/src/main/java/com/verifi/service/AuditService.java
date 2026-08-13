package com.verifi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifi.support.Ids;
import com.verifi.support.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void log(String userId, String action, String resourceType, String resourceId, Map<String, Object> details, String ip) {
        try {
            String encoded = details == null ? null : json.writeValueAsString(details);
            jdbc.update("""
                    INSERT INTO audit_logs (id, user_id, action, resource_type, resource_id, details, ip_address, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.uuid(), userId, action, resourceType, resourceId, encoded, ip, Time.now());
        } catch (Exception ex) {
            log.warn("audit write failed: {}", ex.getMessage());
        }
    }
}
