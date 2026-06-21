package com.flag.common.dto;

import lombok.Data;

import java.util.Map;

/**
 * Request body for feature flag evaluation.
 *
 * Used by client SDK to send evaluation requests to EvalService.
 */
@Data
public class EvaluateRequest {
    /** Unique application identifier */
    private String appId;
    /** Feature flag key */
    private String flagKey;
    /** Target user identifier (for whitelist/grayscale matching) */
    private String userId;
    /** Custom context attributes (for attribute rule matching) */
    private Map<String, String> attributes;
    /**
     * Client-side cached flag version.
     * When cachedVersion >= server entry.version, the evaluation engine
     * can skip rule parsing and directly return the cached result (cache-hit).
     * -1 means the client does not have a cached version (perform full evaluation).
     */
    private int cachedVersion = -1;
}