package com.flag.admin.service;

import com.flag.admin.entity.FeatureFlagEntity;
import com.flag.admin.entity.FlagOutboxEntity;
import com.flag.admin.publisher.FlagChangePublisher;
import com.flag.admin.repository.FeatureFlagRepository;
import com.flag.admin.repository.FlagOutboxRepository;
import com.flag.common.dto.FlagChangeMessage;
import com.flag.common.dto.FlagChangeMessage.ChangeType;
import com.flag.common.exception.BusinessException;
import com.flag.common.exception.ErrorCode;
import com.flag.common.util.RuleConfigValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Feature flag service with transactional outbox.
 *
 * All change events go through the outbox table in the SAME @Transactional
 * as the business data update. A separate OutboxPoller asynchronously
 * delivers them to Redis, eliminating dual-write split-brain risk.
 */
@Service
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    private final FeatureFlagRepository featureFlagRepository;
    private final FlagOutboxRepository outboxRepository;
    private final AppService appService;
    private final FlagChangePublisher publisher;

    public FeatureFlagService(FeatureFlagRepository featureFlagRepository,
                              FlagOutboxRepository outboxRepository,
                              AppService appService,
                              FlagChangePublisher publisher) {
        this.featureFlagRepository = featureFlagRepository;
        this.outboxRepository = outboxRepository;
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
        appService.getByAppId(flag.getAppId());
        if (featureFlagRepository.existsByAppIdAndFlagKey(flag.getAppId(), flag.getFlagKey())) {
            throw new BusinessException(ErrorCode.FLAG_KEY_CONFLICT);
        }
        // Deep-validate ruleConfig against JSON Schema before persisting
        RuleConfigValidator.validate(flag.getRuleConfig());
        FeatureFlagEntity saved = featureFlagRepository.save(flag);
        writeOutbox(ChangeType.CREATE, saved.getAppId(), saved.getFlagKey(), saved.getVersion().longValue());
        log.info("FeatureFlag created: appId={}, flagKey={}, version={}", saved.getAppId(), saved.getFlagKey(), saved.getVersion());
        return saved;
    }

    @Transactional
    public FeatureFlagEntity update(String appId, String flagKey, FeatureFlagEntity update) {
        FeatureFlagEntity existing = getByAppIdAndFlagKey(appId, flagKey);
        existing.setName(update.getName());
        existing.setDescription(update.getDescription());
        existing.setEnabled(update.getEnabled());
        // Deep-validate ruleConfig against JSON Schema before persisting
        RuleConfigValidator.validate(update.getRuleConfig());
        existing.setRuleConfig(update.getRuleConfig());
        FeatureFlagEntity saved = featureFlagRepository.save(existing);
        writeOutbox(ChangeType.UPDATE, appId, flagKey, saved.getVersion().longValue());
        log.info("FeatureFlag updated: appId={}, flagKey={}, newVersion={}", appId, flagKey, saved.getVersion());
        return saved;
    }

    @Transactional
    public void delete(String appId, String flagKey) {
        FeatureFlagEntity existing = getByAppIdAndFlagKey(appId, flagKey);
        featureFlagRepository.delete(existing);
        writeOutbox(ChangeType.DELETE, appId, flagKey, existing.getVersion().longValue());
        log.info("FeatureFlag deleted: appId={}, flagKey={}", appId, flagKey);
    }

    @Transactional
    public FeatureFlagEntity setEnabled(String appId, String flagKey, boolean enabled) {
        FeatureFlagEntity existing = getByAppIdAndFlagKey(appId, flagKey);
        existing.setEnabled(enabled);
        FeatureFlagEntity saved = featureFlagRepository.save(existing);
        writeOutbox(enabled ? ChangeType.ENABLE : ChangeType.DISABLE, appId, flagKey, saved.getVersion().longValue());
        log.info("FeatureFlag {}: appId={}, flagKey={}", enabled ? "enabled" : "disabled", appId, flagKey);
        return saved;
    }

    @Transactional
    public void reloadApp(String appId) {
        appService.getByAppId(appId);
        writeOutbox(ChangeType.RELOAD, appId, null, 0L);
        log.info("Reload triggered for appId={}", appId);
    }

    /**
     * Write a change event to the transactional outbox.
     * This runs in the SAME @Transactional as the business data update,
     * guaranteeing atomicity. If the DB insert fails, the business update rolls back too.
     */
    private void writeOutbox(ChangeType changeType, String appId, String flagKey, Long version) {
        FlagChangeMessage msg = FlagChangeMessage.of(appId, changeType, flagKey, version);
        outboxRepository.save(new FlagOutboxEntity(
                "flag:change",
                publisher.toJson(msg)
        ));
    }
}
