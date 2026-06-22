package com.flag.admin.service;

import com.flag.admin.dto.CreateFlagRequest;
import com.flag.admin.dto.FlagUpdateRequest;
import com.flag.admin.entity.FeatureFlagEntity;
import com.flag.admin.entity.FlagOutboxEntity;
import com.flag.admin.publisher.FlagChangePublisher;
import com.flag.admin.repository.FeatureFlagRepository;
import com.flag.admin.repository.FlagOutboxRepository;
import com.flag.common.dto.FlagChangeMessage;
import com.flag.common.dto.FlagChangeMessage.ChangeType;
import com.flag.common.exception.BusinessException;
import com.flag.common.exception.ErrorCode;
import com.flag.common.model.EvaluationRule;
import com.flag.common.util.RuleConfigValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Feature flag service with transactional outbox.
 * <p>
 * All change events go through the outbox table in the SAME @Transactional
 * as the business data update. A separate OutboxPoller asynchronously
 * delivers them to Redis, eliminating dual-write split-brain risk.
 * <p>
 * Controller-facing methods now accept {@link FlagUpdateRequest} DTOs
 * instead of raw Entities, preventing mass-assignment attacks.
 */
@Service
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    // ========================================================================
    //  DTO-based mutations  (mass-assignment safe)
    // ========================================================================

    /**
     * Create a flag from a CreateFlagRequest DTO.
     * appId comes from @PathVariable, flagKey from the DTO.
     * After creation the flagKey is immutable and only appears in @PathVariable.
     */
    @Transactional
    public void createFromDto(String appId, CreateFlagRequest request) {
        appService.getByAppId(appId);

        if (featureFlagRepository.existsByAppIdAndFlagKey(appId, request.getFlagKey())) {
            throw new BusinessException(ErrorCode.FLAG_KEY_CONFLICT);
        }

        String ruleConfigJson = serializeRules(request.getRules(), request.isDefaultStrategy());
        RuleConfigValidator.validate(ruleConfigJson);

        FeatureFlagEntity entity = new FeatureFlagEntity(
                appId,
                request.getFlagKey(),
                request.getFlagName(),
                request.getDescription(),
                request.getGlobalEnabled(),
                ruleConfigJson,
                null  // createdBy = null (not exposed via API)
        );

        FeatureFlagEntity saved = featureFlagRepository.save(entity);
        writeOutbox(ChangeType.CREATE, appId, request.getFlagKey(), saved.getVersion().longValue());
        log.info("FeatureFlag created from DTO: appId={}, flagKey={}, version={}",
                appId, request.getFlagKey(), saved.getVersion());
    }

    /**
     * Update a flag from a DTO.
     * appId and flagKey come from @PathVariable — the DTO has no such fields.
     * Only modifiable fields (name, description, enabled, rules) are copied.
     */
    @Transactional
    public void updateFromDto(String appId, String flagKey, FlagUpdateRequest request) {
        FeatureFlagEntity existing = getByAppIdAndFlagKey(appId, flagKey);

        // Safe field copy — only what operators are allowed to change
        existing.setName(request.getFlagName());
        existing.setDescription(request.getDescription());
        existing.setEnabled(request.getGlobalEnabled());

        // Serialize the rules list to JSON and store in ruleConfig
        String ruleConfigJson = serializeRules(request.getRules(), request.isDefaultStrategy());
        RuleConfigValidator.validate(ruleConfigJson);
        existing.setRuleConfig(ruleConfigJson);

        FeatureFlagEntity saved = featureFlagRepository.save(existing);
        writeOutbox(ChangeType.UPDATE, appId, flagKey, saved.getVersion().longValue());
        log.info("FeatureFlag updated from DTO: appId={}, flagKey={}, newVersion={}",
                appId, flagKey, saved.getVersion());
    }

    // ========================================================================
    //  Legacy mutations  (still accept raw Entity, kept for backward compat)
    // ========================================================================

    /**
     * @deprecated Use {@link #createFromDto(String, CreateFlagRequest)} instead.
     * This method accepts a raw Entity, exposing audit fields (id, createdAt, updatedAt)
     * and the unique-index flagKey to mass-assignment.
     */
    @Deprecated(since = "2026-06-22", forRemoval = true)
    @Transactional
    public FeatureFlagEntity create(FeatureFlagEntity flag) {
        appService.getByAppId(flag.getAppId());
        if (featureFlagRepository.existsByAppIdAndFlagKey(flag.getAppId(), flag.getFlagKey())) {
            throw new BusinessException(ErrorCode.FLAG_KEY_CONFLICT);
        }
        RuleConfigValidator.validate(flag.getRuleConfig());
        FeatureFlagEntity saved = featureFlagRepository.save(flag);
        writeOutbox(ChangeType.CREATE, saved.getAppId(), saved.getFlagKey(), saved.getVersion().longValue());
        log.info("FeatureFlag created: appId={}, flagKey={}, version={}",
                saved.getAppId(), saved.getFlagKey(), saved.getVersion());
        return saved;
    }

    /**
     * @deprecated Use {@link #updateFromDto(String, String, FlagUpdateRequest)} instead.
     * This method accepts a raw Entity, exposing audit fields and the unique-index
     * flagKey to mass-assignment via HTTP body.
     */
    @Deprecated(since = "2026-06-22", forRemoval = true)
    @Transactional
    public FeatureFlagEntity update(String appId, String flagKey, FeatureFlagEntity update) {
        FeatureFlagEntity existing = getByAppIdAndFlagKey(appId, flagKey);
        existing.setName(update.getName());
        existing.setDescription(update.getDescription());
        existing.setEnabled(update.getEnabled());
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
        writeOutbox(enabled ? ChangeType.ENABLE : ChangeType.DISABLE,
                appId, flagKey, saved.getVersion().longValue());
        log.info("FeatureFlag {}: appId={}, flagKey={}", enabled ? "enabled" : "disabled", appId, flagKey);
        return saved;
    }

    @Transactional
    public void reloadApp(String appId) {
        appService.getByAppId(appId);
        writeOutbox(ChangeType.RELOAD, appId, null, 0L);
        log.info("Reload triggered for appId={}", appId);
    }

    // ========================================================================
    //  Internal helpers
    // ========================================================================

    /**
     * Serialize {@code rules} + {@code defaultStrategy} into the JSONB
     * rule_config column format.
     */
    private String serializeRules(List<EvaluationRule> rules, boolean defaultStrategy) {
        try {
            // Build the JSON structure matching the FlagConfig model:
            // { "defaultStrategy": false, "rules": [...] }
            return MAPPER.writeValueAsString(
                    java.util.Map.of(
                            "defaultStrategy", defaultStrategy,
                            "rules", rules != null ? rules : List.of()
                    )
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize rules to JSON", e);
        }
    }

    private void writeOutbox(ChangeType changeType, String appId, String flagKey, Long version) {
        FlagChangeMessage msg = FlagChangeMessage.of(appId, changeType, flagKey, version);
        outboxRepository.save(new FlagOutboxEntity(
                "flag:change",
                publisher.toJson(msg)
        ));
    }
}