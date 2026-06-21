package com.flag.admin.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Transactional outbox for change events.
 *
 * FeatureFlagService writes to this table in the SAME @Transactional
 * as the business data update, guaranteeing atomicity.
 * A separate scheduled poller reads unsent rows and delivers them to Redis.
 */
@Entity
@Table(name = "flag_outbox")
@Data
@NoArgsConstructor
public class FlagOutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Redis Pub/Sub channel (e.g. "flag:change", "app:change", "flag:reload") */
    @Column(name = "channel", nullable = false, length = 64)
    private String channel;

    /** JSON payload (serialized FlagChangeMessage) */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    /** Whether this event has been successfully delivered to Redis */
    @Column(name = "sent", nullable = false)
    private boolean sent = false;

    /** Number of delivery attempts so far */
    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    /** Max retries before the entry is moved to dead-letter state */
    public static final int MAX_RETRIES = 5;

    /** Dead-letter flag: true means all retries exhausted, manual intervention needed */
    @Column(name = "dead_letter", nullable = false)
    private boolean deadLetter = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    public FlagOutboxEntity(String channel, String payload) {
        this.channel = channel;
        this.payload = payload;
    }
}
