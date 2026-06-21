package com.flag.worker.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ClickHouse DataSource Configuration.
 *
 * Corresponds to architecture diagram: MetricsWorker -> Batch write to ClickHouse
 *
 * Uses ClickHouse JDBC driver + HikariCP connection pool.
 */
@Configuration
public class ClickHouseConfig {

    @Value("${app.clickhouse.url}")
    private String url;

    @Value("${app.clickhouse.username}")
    private String username;

    @Value("${app.clickhouse.password}")
    private String password;

    @Value("${app.clickhouse.driver-class}")
    private String driverClass;

    @Bean(name = "clickHouseDataSource")
    public DataSource clickHouseDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driverClass);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        config.setPoolName("ClickHousePool");
        return new HikariDataSource(config);
    }

    @Bean(name = "clickHouseJdbcTemplate")
    public JdbcTemplate clickHouseJdbcTemplate(@Qualifier("clickHouseDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}