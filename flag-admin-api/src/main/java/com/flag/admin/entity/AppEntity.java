package com.flag.admin.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Application registry.
 * Each accessing party (backend microservice / mobile client / Web client) must register the App in advance,
 * obtain a unique appId, and all subsequent rules are isolated by appId.
 */
@Entity
@Table(name = "flag_app", uniqueConstraints = {
        @UniqueConstraint(name = "uk_app_id", columnNames = "app_id")
})
@Data
@NoArgsConstructor
public class AppEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Business unique identifier, used during SDK initialization */
    @Column(name = "app_id", nullable = false, length = 64)
    private String appId;

    /** Application name */
    @Column(name = "app_name", nullable = false, length = 128)
    private String appName;

    /** Application description */
    @Column(name = "description", length = 512)
    private String description;

    /** Application type: BACKEND / MOBILE / WEB */
    @Column(name = "app_type", nullable = false, length = 32)
    private String appType;

    /** Whether enabled */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "timestamptz")
    private Instant updatedAt;

    public AppEntity(String appId, String appName, String description, String appType) {
        this.appId = appId;
        this.appName = appName;
        this.description = description;
        this.appType = appType;
        this.enabled = true;
    }
}