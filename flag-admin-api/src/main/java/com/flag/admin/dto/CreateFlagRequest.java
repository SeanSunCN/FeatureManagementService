package com.flag.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Feature flag creation request DTO.
 * <p>
 * Extends {@link FlagUpdateRequest} with a {@code flagKey} field.
 * The flagKey is required at creation time but is immutable thereafter —
 * updates use {@link FlagUpdateRequest} which does NOT carry flagKey.
 * <p>
 * appId is NOT included — it comes from @PathVariable.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CreateFlagRequest extends FlagUpdateRequest {

    @NotBlank(message = "flagKey must not be blank")
    private String flagKey;
}