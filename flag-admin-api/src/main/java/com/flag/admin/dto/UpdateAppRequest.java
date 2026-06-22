package com.flag.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Application update request DTO.
 * <p>
 * appId is NOT included — it comes from @PathVariable and is immutable.
 * Audit fields (id, createdAt, updatedAt) are excluded to prevent
 * mass-assignment attacks.
 */
@Data
public class UpdateAppRequest {

    @Size(max = 128, message = "appName length must not exceed 128")
    private String appName;

    @Size(max = 512, message = "description length must not exceed 512")
    private String description;

    @Pattern(regexp = "^(BACKEND|MOBILE|WEB)$", message = "appType must be one of: BACKEND, MOBILE, WEB")
    private String appType;

    private Boolean enabled;
}