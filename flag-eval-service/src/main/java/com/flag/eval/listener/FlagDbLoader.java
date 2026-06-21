package com.flag.eval.listener;

import com.flag.eval.cache.FlagCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Database rule loader.
 * <p>
 * Responsibilities:
 * 1. Load all rules from PostgreSQL into memory cache on application startup
 * 2. Incrementally load a single flag based on Redis change notifications at runtime
 * <p>
 * Uses JdbcTemplate to connect directly to the DB (not JPA / not R2DBC).
 * Reads only at startup and on change; zero DB calls at runtime.
 */
@Component
public class FlagDbLoader {

    private static final Logger log = LoggerFactory.getLogger(FlagDbLoader.class);

    private final JdbcTemplate jdbcTemplate;

    public FlagDbLoader(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * Load all App rules into cache on startup.
     */
    public Map<String, Map<String, FlagCache.FlagEntry>> loadAll() {
        Map<String, Map<String, FlagCache.FlagEntry>> allFlags = new HashMap<>();

        String sql = """
                SELECT f.app_id, f.flag_key, f.name, f.enabled,
                       f.rule_config AS rule_config, f.version
                FROM flag_feature f
                WHERE f.enabled = true
                """;

        jdbcTemplate.query(sql, rs -> {
            String appId = rs.getString("app_id");
            String flagKey = rs.getString("flag_key");
            String name = rs.getString("name");
            boolean enabled = rs.getBoolean("enabled");
            String ruleConfig = rs.getString("rule_config");
            int version = rs.getInt("version");

            FlagCache.FlagEntry entry = FlagCache.FlagEntry.fromEntity(
                    flagKey, name, enabled, ruleConfig, version);

            allFlags.computeIfAbsent(appId, k -> new HashMap<>())
                    .put(flagKey, entry);
        });

        log.info("Loaded {} apps with total flags from database", allFlags.size());
        return allFlags;
    }

    /**
     * Load all flags for the specified App.
     */
    public Map<String, FlagCache.FlagEntry> loadAllFlags(String appId) {
        Map<String, FlagCache.FlagEntry> flags = new HashMap<>();

        String sql = """
                SELECT f.flag_key, f.name, f.enabled,
                       f.rule_config AS rule_config, f.version
                FROM flag_feature f
                WHERE f.app_id = ?
                """;

        jdbcTemplate.query(sql, new Object[]{appId}, rs -> {
            String flagKey = rs.getString("flag_key");
            String name = rs.getString("name");
            boolean enabled = rs.getBoolean("enabled");
            String ruleConfig = rs.getString("rule_config");
            int version = rs.getInt("version");

            flags.put(flagKey, FlagCache.FlagEntry.fromEntity(
                    flagKey, name, enabled, ruleConfig, version));
        });

        log.info("Loaded {} flags for appId={}", flags.size(), appId);
        return flags;
    }

    /**
     * Load a single flag.
     */
    public FlagCache.FlagEntry loadSingleFlag(String appId, String flagKey) {
        String sql = """
                SELECT f.flag_key, f.name, f.enabled,
                       f.rule_config AS rule_config, f.version
                FROM flag_feature f
                WHERE f.app_id = ? AND f.flag_key = ?
                """;

        return jdbcTemplate.query(sql, new Object[]{appId, flagKey}, rs -> {
            if (rs.next()) {
                return FlagCache.FlagEntry.fromEntity(
                        rs.getString("flag_key"),
                        rs.getString("name"),
                        rs.getBoolean("enabled"),
                        rs.getString("rule_config"),
                        rs.getInt("version")
                );
            }
            return null;
        });
    }
}