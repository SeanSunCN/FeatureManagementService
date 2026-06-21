package com.flag.admin.repository;

import com.flag.admin.entity.FlagOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FlagOutboxRepository extends JpaRepository<FlagOutboxEntity, Long> {

    /**
     * Find unsent, non-dead-letter entries that haven't exceeded max retries.
     * Sorted by creation time to guarantee FIFO delivery order.
     */
    @Query("SELECT o FROM FlagOutboxEntity o " +
           "WHERE o.sent = false " +
           "  AND o.deadLetter = false " +
           "  AND o.retryCount < :maxRetries " +
           "ORDER BY o.createdAt ASC")
    List<FlagOutboxEntity> findPending(@Param("maxRetries") int maxRetries);

    @Modifying
    @Query("UPDATE FlagOutboxEntity o SET o.sent = true WHERE o.id = :id")
    void markSent(Long id);

    @Modifying
    @Query("UPDATE FlagOutboxEntity o SET o.retryCount = o.retryCount + 1 WHERE o.id = :id")
    void incrementRetryCount(Long id);

    @Modifying
    @Query("UPDATE FlagOutboxEntity o SET o.deadLetter = true, o.retryCount = o.retryCount + 1 WHERE o.id = :id")
    void markDeadLetter(Long id);
}
