package com.flag.admin.service;

import com.flag.admin.entity.FeatureFlagEntity;
import com.flag.admin.publisher.FlagChangePublisher;
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
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    private final FeatureFlagRepository featureFlagRepository;
    private final AppService appService;
    private final FlagChangePublisher publisher;

    public FeatureFlagService(FeatureFlagRepository featureFlagRepository,
                              AppService appService,
                              FlagChangePublisher publisher) {
        this.featureFlagRepository = featureFlagRepository;
        this.appService = appService;
        this.publisher = publisher;
    }

    public List<FeatureFlagEntity> listByAppId(String appId) {
        appService.getByAppId(appId);
        return featureFlagRepository.findByAppId(appId);
    }

    public FeatureFlagEntity getByAppIdAndFlagKey(String appId, String flagKey) {
        return featureFlagRepository.findByAppIdAndFlagKey(appId, flagKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.FLAG_NOT_FOUND));
    }

    @Transactional
    public FeatureFlagEntity create(FeatureFlagEntity flag) {
        // Verify App exists
        appService.getByAppId(flag.getAppId());

        if (featureFlagRepository.existsByAppIdAndFlagKey(flag.getAppId(), flag.getFlagKey())) {
            throw new BusinessException(ErrorCode.FLAG_KEY_CONFLICT);
        }

        FeatureFlagEntity saved = featureFlagRepository.save(flag);
        publisher.publishFlagChange(saved.getAppId(), ChangeType.CREATE, saved.getFlagKey(), saved.getVersion().longValue());
        log.info("FeatureFlag created: appId={}, flagKey={}, version={}", saved.getAppId(), saved.getFlagKey(), saved.getVersion());
        return saved;
    }

    @Transactional
    public FeatureFlagEntity update(String appId, String flagKey, FeatureFlagEntity update) {
        FeatureFlagEntity existing = getByAppIdAndFlagKey(appId, flagKey);

        existing.setName(update.getName());
        existing.setDescription(update.getDescription());
        existing.setEnabled(update.getEnabled());
        existing.setRuleConfig(update.getRuleConfig());
        // version is auto-incremented by @Version

        FeatureFlagEntity saved = featureFlagRepository.save(existing);
        publisher.publishFlagChange(appId, ChangeType.UPDATE, flagKey, saved.getVersion().longValue());
        log.info("FeatureFlag updated: appId={}, flagKey={}, newVersion={}",
                appId, flagKey, saved.getVersion());
        return saved;
    }

    @Transactional
    public void delete(String appId, String flagKey) {
        FeatureFlagEntity existing = getByAppIdAndFlagKey(appId, flagKey);
        featureFlagRepository.delete(existing);
        publisher.publishFlagChange(appId, ChangeType.DELETE, flagKey, existing.getVersion().longValue());
        log.info("FeatureFlag deleted: appId={}, flagKey={}", appId, flagKey);
    }

    @Transactional
    public FeatureFlagEntity setEnabled(String appId, String flagKey, boolean enabled) {
        FeatureFlagEntity existing = getByAppIdAndFlagKey(appId, flagKey);
        existing.setEnabled(enabled);
        FeatureFlagEntity saved = featureFlagRepository.save(existing);
        publisher.publishFlagChange(appId, enabled ? ChangeType.ENABLE : ChangeType.DISABLE, flagKey, saved.getVersion().longValue());
        log.info("FeatureFlag {}: appId={}, flagKey={}", enabled ? "enabled" : "disabled", appId, flagKey);
        return saved;
    }

    /**
     * Trigger a full reload for the specified App (used for data-plane disaster recovery forced sync).
     */
    @Transactional
    public void reloadApp(String appId) {
        appService.getByAppId(appId);
        publisher.publishReload(appId);
        log.info("Reload triggered for appId={}", appId);
    }
}