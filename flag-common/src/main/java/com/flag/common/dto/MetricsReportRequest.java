package com.flag.common.dto;

import lombok.Data;

import java.util.Map;

/**
 * Request body for metrics reporting from client SDK / EvalService.
 */
@Data
public class MetricsReportRequest {
    /** Unique application identifier */
    private String appId;
    /** Feature flag evaluation counts: Map<flagKey, hit count> */
    private Map<String, Long> flagHitCounts;
}