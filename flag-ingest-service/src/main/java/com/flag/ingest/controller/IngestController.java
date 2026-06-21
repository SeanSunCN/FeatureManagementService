package com.flag.ingest.controller;

import com.flag.common.dto.AuditLogEntry;
import com.flag.common.dto.MetricsReportRequest;
import com.flag.common.exception.ErrorCode;
import com.flag.common.response.UnifiedResponse;
import com.flag.ingest.channel.AuditLogChannel;
import com.flag.ingest.channel.MetricsChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Data reporting entry API.
 * <p>
 * Corresponding architecture: LB routes reporting traffic -> IngestService (unified entry)
 * <p>
 * Internally, it uses the dual-channel split processing via MetricsChannel and AuditLogChannel.
 */
@RestController
@RequestMapping("/api/v1/ingest")
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final MetricsChannel metricsChannel;
    private final AuditLogChannel auditLogChannel;

    public IngestController(MetricsChannel metricsChannel, AuditLogChannel auditLogChannel) {
        this.metricsChannel = metricsChannel;
        this.auditLogChannel = auditLogChannel;
    }

    /**
     * POST /api/v1/ingest/metrics
     * SDK reports feature flag evaluation metrics.
     */
    @PostMapping("/metrics")
    public UnifiedResponse<Void> reportMetrics(@RequestBody MetricsReportRequest request) {
        metricsChannel.ingest(request);
        return UnifiedResponse.success();
    }

    /**
     * POST /api/v1/ingest/audit-log
     * SDK reports audit log (single entry).
     */
    @PostMapping("/audit-log")
    public UnifiedResponse<Void> reportAuditLog(@RequestBody AuditLogEntry entry) {
        auditLogChannel.ingest(entry);
        return UnifiedResponse.success();
    }

    /**
     * POST /api/v1/ingest/audit-log/batch
     * SDK reports audit logs in batch.
     */
    @PostMapping("/audit-log/batch")
    public UnifiedResponse<Void> reportAuditLogBatch(@RequestBody List<AuditLogEntry> entries) {
        for (AuditLogEntry entry : entries) {
            auditLogChannel.ingest(entry);
        }
        return UnifiedResponse.success();
    }

    /**
     * GET /api/v1/ingest/drop-total
     * Get total number of discarded audit logs (for monitoring/self-check).
     */
    @GetMapping("/drop-total")
    public UnifiedResponse<Long> getDropTotal() {
        return UnifiedResponse.success(auditLogChannel.getDropTotal());
    }
}