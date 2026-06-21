package com.flag.common.dto;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Runtime context passed into every evaluate() call.
 * <p>
 * Carries the requesting user's identity and any additional
 * attributes needed for condition matching.
 */
@Value
@Builder
public class EvaluationContext {

    String userId;
    Map<String, String> attributes;
    Long cachedVersion;
}