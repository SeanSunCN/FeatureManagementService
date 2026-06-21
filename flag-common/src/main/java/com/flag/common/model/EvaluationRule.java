package com.flag.common.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * A single evaluation rule inside a FlagConfig.
 * <p>
 * Rules are evaluated top-down (OR / if-elseif chain).
 * The first rule whose conditions all pass (AND within the rule)
 * short-circuits and returns its serveValue.
 */
@Value
@Builder
public class EvaluationRule {

    String ruleId;
    String ruleName;
    boolean serveValue;
    List<Condition> conditions;
}