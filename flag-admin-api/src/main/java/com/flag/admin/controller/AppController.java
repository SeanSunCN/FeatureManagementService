package com.flag.admin.controller;

import com.flag.admin.entity.AppEntity;
import com.flag.admin.service.AppService;
import com.flag.common.response.UnifiedResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Application management API.
 *
 * Corresponding architecture diagram: Control plane Admin API — app registration/query/deletion
 */
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

    @PostMapping
    public UnifiedResponse<AppEntity> createApp(@RequestBody AppEntity app) {
        return UnifiedResponse.success(appService.create(app));
    }

    @PutMapping("/{appId}")
    public UnifiedResponse<AppEntity> updateApp(@PathVariable String appId,
                                                @RequestBody AppEntity app) {
        return UnifiedResponse.success(appService.update(appId, app));
    }

    @DeleteMapping("/{appId}")
    public UnifiedResponse<Void> deleteApp(@PathVariable String appId) {
        appService.deleteByAppId(appId);
        return UnifiedResponse.success();
    }
}