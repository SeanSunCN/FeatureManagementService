package com.flag.admin.controller;

import com.flag.admin.dto.CreateAppRequest;
import com.flag.admin.dto.UpdateAppRequest;
import com.flag.admin.entity.AppEntity;
import com.flag.admin.service.AppService;
import com.flag.common.response.UnifiedResponse;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Application management API.
 * <p>
 * Architecture: Control plane Admin API — app registration / query / update / deletion.
 * All mutations accept a dedicated DTO instead of the raw Entity
 * to prevent mass-assignment attacks.
 */
@Validated
@RestController
@RequestMapping("/api/v1/apps")
public class AppController {

    private final AppService appService;

    public AppController(AppService appService) {
        this.appService = appService;
    }

    @GetMapping
    public UnifiedResponse<List<AppEntity>> listApps() {
        return UnifiedResponse.success(appService.listAll());
    }

    @GetMapping("/{appId}")
    public UnifiedResponse<AppEntity> getApp(@PathVariable String appId) {
        return UnifiedResponse.success(appService.getByAppId(appId));
    }

    /**
     * Create a new application.
     * appId is required in the request body at creation time.
     * After creation the appId is immutable and only appears in @PathVariable.
     */
    @PostMapping
    public UnifiedResponse<AppEntity> createApp(@Valid @RequestBody CreateAppRequest request) {
        return UnifiedResponse.success(appService.createFromDto(request));
    }

    /**
     * Update an existing application.
     * appId comes from @PathVariable — the DTO intentionally lacks appId,
     * preventing illegal modification of the unique-index field via HTTP body.
     */
    @PutMapping("/{appId}")
    public UnifiedResponse<AppEntity> updateApp(@PathVariable String appId,
                                                @Valid @RequestBody UpdateAppRequest request) {
        return UnifiedResponse.success(appService.updateFromDto(appId, request));
    }

    @DeleteMapping("/{appId}")
    public UnifiedResponse<Void> deleteApp(@PathVariable String appId) {
        appService.deleteByAppId(appId);
        return UnifiedResponse.success();
    }
}