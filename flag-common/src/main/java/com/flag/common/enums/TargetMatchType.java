package com.flag.common.enums;

/**
 * Rule matching strategy types.
 * Maps directly from ruleConfig JSON "strategy" field.
 * No hard-coded strings allowed — always reference via this enum.
 */
public enum TargetMatchType {
    BOOLEAN,          // Simple boolean toggle
    GRADUAL_ROLLOUT,  // Percentage-based grayscale rollout
    TARGETING,        // User whitelist + attribute condition matching
}
