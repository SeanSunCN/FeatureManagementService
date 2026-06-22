package com.flag.admin.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Feature flag rule entity.
 * <p>
 * rule_config uses a JSONB column to store flexible targeting rule configuration. Example structure:
 * <pre>
 * {
 *   "strategy": "gradual_rollout",
 *   "percentage": 50,
 *   "conditions": [
 *     {"type": "user_id", "values": ["1001-2000"]},
 *     {"type": "attribute", "key": "region", "values": ["us-east"]}
 *   ],
 *   "default": false
 * }
 * </pre>
 */
@Entity
@Table(name = "flag_feature", uniqueConstraints = {
        @UniqueConstraint(name = "uk_app_flag_key", columnNames = {"app_id", "flag_key"})
})
@Data
@NoArgsConstructor
public class FeatureFlagEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Belonging App */
    @Column(name = "app_id", nullable = false, length = 64)
    private String appId;

    /** Unique feature flag key, unique within the same App */
    @Column(name = "flag_key", nullable = false, length = 128)
    private String flagKey;

    /** Feature flag display name */
    @Column(name = "name", nullable = false, length = 256)
    private String name;

    /** Feature description */
    @Column(name = "description", length = 1024)
    private String description;

    /** Global enable/disable toggle */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = false;

    /**
     * JSONB rule configuration.
     * Uses @JdbcTypeCode(SqlTypes.JSON) to let Hibernate handle serialization/deserialization automatically.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_config", columnDefinition = "jsonb")
    private String ruleConfig;

    /** Optimistic locking version number */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    /** Safe for client-side (Web SDK) exposure: false=server-only, true=allow frontend pull */
    @Column(name = "safe_for_client", nullable = false)
    private Boolean safeForClient = false;

    /** Created by */
    @Column(name = "created_by", length = 64)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "timestamptz")
    private Instant updatedAt;

    public FeatureFlagEntity(String appId, String flagKey, String name, String description,
                             Boolean enabled, String ruleConfig, Boolean safeForClient, String createdBy) {
        this.appId = appId;
        this.flagKey = flagKey;
        this.name = name;
        this.description = description;
        this.enabled = enabled;
        this.ruleConfig = ruleConfig;
        this.safeForClient = safeForClient != null ? safeForClient : false;
        this.createdBy = createdBy;
        this.version = 0;
    }
}