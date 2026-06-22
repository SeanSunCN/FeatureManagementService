package com.flag.admin.service;

import com.flag.admin.dto.CreateAppRequest;
import com.flag.admin.dto.UpdateAppRequest;
import com.flag.admin.entity.AppEntity;
import com.flag.admin.publisher.FlagChangePublisher;
import com.flag.admin.repository.AppRepository;
import com.flag.admin.repository.FeatureFlagRepository;
import com.flag.common.dto.FlagChangeMessage.ChangeType;
import com.flag.common.exception.BusinessException;
import com.flag.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AppService {

    private static final Logger log = LoggerFactory.getLogger(AppService.class);

    private final AppRepository appRepository;
    private final FeatureFlagRepository featureFlagRepository;
    private final FlagChangePublisher publisher;

    public AppService(AppRepository appRepository,
                      FeatureFlagRepository featureFlagRepository,
                      FlagChangePublisher publisher) {
        this.appRepository = appRepository;
        this.featureFlagRepository = featureFlagRepository;
        this.publisher = publisher;
    }

    public List<AppEntity> listAll() {
        return appRepository.findAll();
    }

    public AppEntity getByAppId(String appId) {
        return appRepository.findByAppId(appId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APP_NOT_FOUND));
    }

    public AppEntity getById(Long id) {
        return appRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.APP_NOT_FOUND));
    }

    // ========================================================================
    //  DTO-based mutations  (mass-assignment safe)
    // ========================================================================

    /**
     * Create an app from a CreateAppRequest DTO.
     * Only allows fields that operators should set at creation time.
     * Audit fields (id, createdAt, updatedAt) have no corresponding DTO fields.
     */
    @Transactional
    public AppEntity createFromDto(CreateAppRequest request) {
        if (appRepository.existsByAppId(request.getAppId())) {
            throw new BusinessException(ErrorCode.APP_ID_CONFLICT);
        }
        AppEntity entity = new AppEntity(
                request.getAppId(),
                request.getAppName(),
                request.getDescription(),
                request.getAppType()
        );
        AppEntity saved = appRepository.save(entity);
        publisher.publishAppChange(saved.getAppId(), ChangeType.CREATE);
        log.info("App created from DTO: appId={}", saved.getAppId());
        return saved;
    }

    /**
     * Update an app from an UpdateAppRequest DTO.
     * appId comes from the caller (URL PathVariable), NOT from the DTO,
     * preventing illegal modification of the unique-index field via HTTP body.
     * Only modifiable fields (appName, description, appType, enabled) are copied.
     */
    @Transactional
    public AppEntity updateFromDto(String appId, UpdateAppRequest request) {
        AppEntity existing = getByAppId(appId);
        if (request.getAppName() != null) existing.setAppName(request.getAppName());
        if (request.getDescription() != null) existing.setDescription(request.getDescription());
        if (request.getAppType() != null) existing.setAppType(request.getAppType());
        if (request.getEnabled() != null) existing.setEnabled(request.getEnabled());
        AppEntity saved = appRepository.save(existing);
        publisher.publishAppChange(appId, ChangeType.UPDATE);
        log.info("App updated from DTO: appId={}", appId);
        return saved;
    }

    // ========================================================================
    //  Legacy mutations  (still accept raw Entity, kept for backward compat)
    // ========================================================================

    /**
     * @deprecated Use {@link #createFromDto(CreateAppRequest)} instead.
     * This method accepts a raw Entity, exposing audit fields to mass-assignment.
     */
    @Deprecated(since = "2026-06-22", forRemoval = true)
    @Transactional
    public AppEntity create(AppEntity app) {
        if (appRepository.existsByAppId(app.getAppId())) {
            throw new BusinessException(ErrorCode.APP_ID_CONFLICT);
        }
        AppEntity saved = appRepository.save(app);
        publisher.publishAppChange(saved.getAppId(), ChangeType.CREATE);
        log.warn("App created via DEPRECATED Entity method: appId={}", saved.getAppId());
        return saved;
    }

    /**
     * @deprecated Use {@link #updateFromDto(String, UpdateAppRequest)} instead.
     * This method accepts a raw Entity, exposing audit fields to mass-assignment.
     */
    @Deprecated(since = "2026-06-22", forRemoval = true)
    @Transactional
    public AppEntity update(String appId, AppEntity update) {
        AppEntity existing = getByAppId(appId);
        existing.setAppName(update.getAppName());
        existing.setDescription(update.getDescription());
        existing.setAppType(update.getAppType());
        existing.setEnabled(update.getEnabled());
        AppEntity saved = appRepository.save(existing);
        publisher.publishAppChange(appId, ChangeType.UPDATE);
        log.warn("App updated via DEPRECATED Entity method: appId={}", appId);
        return saved;
    }

    @Transactional
    public void deleteByAppId(String appId) {
        AppEntity app = getByAppId(appId);
        // Manually cascade-delete all flags under this app before deleting the app
        // (no JPA cascade defined in entity)
        featureFlagRepository.deleteByAppId(appId);
        appRepository.delete(app);
        publisher.publishAppChange(appId, ChangeType.DELETE);
        log.info("App deleted (with cascade flags): appId={}", appId);
    }
}