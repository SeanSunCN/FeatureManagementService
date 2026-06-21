package com.flag.common.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Full feature flag configuration — the big object loaded into cache.
 * <p>
 * Retrieved by (appId, flagKey) as the unique key.
 * Contains the global toggle, an ordered rule list with OR semantics,
 * and the default value when no rule matches.
 */
@Value
@Builder
public class FlagConfig {

    String appId;
    String flagKey;
    boolean globalEnabled;
    boolean defaultServeValue;
    List<EvaluationRule> rules;
}