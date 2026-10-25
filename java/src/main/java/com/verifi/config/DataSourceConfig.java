package com.verifi.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class DataSourceConfig {
    @Bean
    public DataSource dataSource(AppProperties props) throws Exception {
        String url = props.getDatasourceUrl();
        if (url == null || url.isBlank()) {
            Path dir = Path.of("data");
            Files.createDirectories(dir);
            String path = dir.toAbsolutePath().resolve("verifi.db").toString().replace('\\', '/');
            url = "jdbc:sqlite:" + path + "?foreign_keys=on&busy_timeout=5000";
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setPoolName("verifi");
        ds.setMaximumPoolSize(1);
        ds.setMinimumIdle(1);
        ds.setConnectionTestQuery("SELECT 1");
        return ds;
    }
}
