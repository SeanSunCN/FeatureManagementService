package com.flag.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Application creation request DTO.
 * <p>
 * appId is allowed at creation time (once set, it becomes the URL PathVariable
 * and is immutable thereafter). The DTO intentionally excludes {@code id},
 * {@code createdAt}, {@code updatedAt} and other audit fields to prevent
 * mass-assignment attacks.
 */
@Data
public class CreateAppRequest {

    @NotBlank(message = "appId must not be blank")
    @Size(min = 2, max = 64, message = "appId length must be between 2 and 64")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "appId may only contain letters, digits, hyphens and underscores")
    private String appId;

    @NotBlank(message = "appName must not be blank")
    @Size(max = 128, message = "appName length must not exceed 128")
    private String appName;

    @Size(max = 512, message = "description length must not exceed 512")
    private String description;

    @NotBlank(message = "appType must not be blank")
    @Pattern(regexp = "^(BACKEND|MOBILE|WEB)$", message = "appType must be one of: BACKEND, MOBILE, WEB")
    private String appType;
}
