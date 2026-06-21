package com.flag.admin.dto;

import com.flag.common.model.Condition;
import com.flag.common.model.EvaluationRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Feature flag update request DTO.
 * <p>
 * Contains ONLY the fields that operators are allowed to modify.
 * <ul>
 *   <li>{@code appId} and {@code flagKey} are NOT included — they come from @PathVariable</li>
 *   <li>{@code id}, {@code version}, {@code createdAt}, {@code updatedAt} are NOT included</li>
 * </ul>
 * This prevents mass-assignment / parameter tampering attacks.
 */
@Data
public class FlagUpdateRequest {

    /** Feature flag display name */
    @NotBlank(message = "flagName must not be blank")
    private String flagName;

    /** Feature description */
    private String description;

    /** Global enable/disable toggle */
    @NotNull(message = "globalEnabled must not be null")
    private Boolean globalEnabled;

    /** Default serve value when no rule matches */
    private boolean defaultStrategy;

    /**
     * Ordered rule list (OR semantic between rules, AND within each rule).
     * Serialized to JSONB and stored in the rule_config column.
     */
    @Valid
    private List<EvaluationRule> rules;
}