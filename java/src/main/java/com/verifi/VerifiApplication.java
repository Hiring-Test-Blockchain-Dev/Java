package com.verifi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VerifiApplication {
    private static final Logger log = LoggerFactory.getLogger(VerifiApplication.class);

    static {
        try {
            Files.createDirectories(Path.of("data"));
        } catch (Exception ignored) {
            // The datasource path may be absolute in tests.
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(VerifiApplication.class, args);
    }

    @Bean
    org.springframework.boot.ApplicationRunner started(Environment environment) {
        return args -> log.info("Server running on http://localhost:{}", environment.getProperty("local.server.port", environment.getProperty("server.port", "3001")));
    }
}
