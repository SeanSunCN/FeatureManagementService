package com.flag.admin.dto;

import lombok.Data;

/**
 * Feature flag enable/disable request body.
 *
 * Used for PATCH /api/v1/apps/{appId}/flags/{flagKey}/enabled
 */
@Data
public class FlagEnabledRequest {
    private boolean enabled;
}
