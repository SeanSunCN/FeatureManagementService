package com.flag.admin.controller;

import com.flag.admin.dto.FlagEnabledRequest;
import com.flag.admin.entity.FeatureFlagEntity;
import com.flag.admin.service.FeatureFlagService;
import com.flag.common.response.UnifiedResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Feature flag rule management API.
 *
 * Corresponding architecture diagram: Control plane Admin API — rule CRUD
 */
@RestController
@RequestMapping("/api/v1/apps/{appId}/flags")
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    public FeatureFlagController(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    @GetMapping
    public UnifiedResponse<List<FeatureFlagEntity>> listFlags(@PathVariable String appId) {
        return UnifiedResponse.success(featureFlagService.listByAppId(appId));
    }

    @GetMapping("/{flagKey}")
    public UnifiedResponse<FeatureFlagEntity> getFlag(@PathVariable String appId,
                                                      @PathVariable String flagKey) {
        return UnifiedResponse.success(featureFlagService.getByAppIdAndFlagKey(appId, flagKey));
    }

    @PostMapping
    public UnifiedResponse<FeatureFlagEntity> createFlag(@PathVariable String appId,
                                                         @RequestBody FeatureFlagEntity flag) {
        flag.setAppId(appId);
        return UnifiedResponse.success(featureFlagService.create(flag));
    }

    @PutMapping("/{flagKey}")
    public UnifiedResponse<FeatureFlagEntity> updateFlag(@PathVariable String appId,
                                                         @PathVariable String flagKey,
                                                         @RequestBody FeatureFlagEntity flag) {
        return UnifiedResponse.success(featureFlagService.update(appId, flagKey, flag));
    }

    @DeleteMapping("/{flagKey}")
    public UnifiedResponse<Void> deleteFlag(@PathVariable String appId,
                                            @PathVariable String flagKey) {
        featureFlagService.delete(appId, flagKey);
        return UnifiedResponse.success();
    }

    @PatchMapping("/{flagKey}/enabled")
    public UnifiedResponse<FeatureFlagEntity> setEnabled(@PathVariable String appId,
                                                         @PathVariable String flagKey,
                                                         @RequestBody FlagEnabledRequest request) {
        return UnifiedResponse.success(featureFlagService.setEnabled(appId, flagKey, request.isEnabled()));
    }

    @PostMapping("/reload")
    public UnifiedResponse<Void> reloadAppFlags(@PathVariable String appId) {
        featureFlagService.reloadApp(appId);
        return UnifiedResponse.success();
    }
}