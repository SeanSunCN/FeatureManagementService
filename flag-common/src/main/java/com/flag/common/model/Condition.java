package com.flag.common.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * A single matching condition.
 * <p>
 * Multiple conditions inside a rule are AND — all must pass.
 */
@Value
@Builder
public class Condition {

    public enum Operator {
        EQUALS,
        NOT_EQUALS,
        IN,
        NOT_IN,
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL,
        LESS_THAN,
        LESS_THAN_OR_EQUAL
    }

    String attribute;
    Operator operator;
    List<String> values;
}