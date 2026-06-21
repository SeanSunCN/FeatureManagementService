package com.flag.admin.controller;

import com.flag.admin.dto.CreateFlagRequest;
import com.flag.admin.dto.FlagEnabledRequest;
import com.flag.admin.dto.FlagUpdateRequest;
import com.flag.admin.service.FeatureFlagService;
import com.flag.common.response.UnifiedResponse;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Feature flag rule management API.
 * <p>
 * Architecture: Control plane Admin API — rule CRUD
 * <p>
 * All mutations accept a dedicated DTO instead of the raw Entity
 * to prevent mass-assignment attacks.
 */
@Validated
@RestController
@RequestMapping("/api/v1/apps/{appId}/flags")
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    public FeatureFlagController(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    @GetMapping
    public UnifiedResponse<List<?>> listFlags(@PathVariable String appId) {
        return UnifiedResponse.success(featureFlagService.listByAppId(appId));
    }

    @GetMapping("/{flagKey}")
    public UnifiedResponse<?> getFlag(@PathVariable String appId,
                                      @PathVariable String flagKey) {
        return UnifiedResponse.success(featureFlagService.getByAppIdAndFlagKey(appId, flagKey));
    }

    /**
     * Create a new feature flag.
     * appId comes from @PathVariable, flagKey from the DTO (required at creation time).
     */
    @PostMapping
    public UnifiedResponse<Void> createFlag(@PathVariable String appId,
                                            @Valid @RequestBody CreateFlagRequest request) {
        featureFlagService.createFromDto(appId, request);
        return UnifiedResponse.success();
    }

    /**
     * Update an existing feature flag.
     * appId and flagKey come from @PathVariable — the DTO intentionally lacks them,
     * preventing illegal modification of unique-index fields via HTTP body.
     */
    @PutMapping("/{flagKey}")
    public UnifiedResponse<Void> updateFlag(@PathVariable String appId,
                                            @PathVariable String flagKey,
                                            @Valid @RequestBody FlagUpdateRequest request) {
        featureFlagService.updateFromDto(appId, flagKey, request);
        return UnifiedResponse.success();
    }

    @DeleteMapping("/{flagKey}")
    public UnifiedResponse<Void> deleteFlag(@PathVariable String appId,
                                            @PathVariable String flagKey) {
        featureFlagService.delete(appId, flagKey);
        return UnifiedResponse.success();
    }

    @PatchMapping("/{flagKey}/enabled")
    public UnifiedResponse<Void> setEnabled(@PathVariable String appId,
                                            @PathVariable String flagKey,
                                            @RequestBody FlagEnabledRequest request) {
        featureFlagService.setEnabled(appId, flagKey, request.isEnabled());
        return UnifiedResponse.success();
    }

    @PostMapping("/reload")
    public UnifiedResponse<Void> reloadAppFlags(@PathVariable String appId) {
        featureFlagService.reloadApp(appId);
        return UnifiedResponse.success();
    }
}