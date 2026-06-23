package com.flag.admin.cdn;

import com.flag.admin.entity.FeatureFlagEntity;
import com.flag.admin.repository.FeatureFlagRepository;
import com.flag.common.model.FlagConfig;
import com.flag.common.util.RuleConfigParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CDN snapshot publishing service — executed after any flag mutation.
 *
 * <p>Responsibility chain:</p>
 * <ol>
 *   <li><b>SQL-level filter:</b> Queries only flags with {@code safe_for_client = true}
 *       via {@link FeatureFlagRepository#findBySafeForClientTrue()}. Never loads
 *       server-only flags into memory.</li>
 *   <li><b>Conversion:</b> Maps entities to {@link FlagConfig} domain objects via
 *       {@link RuleConfigParser}.</li>
 *   <li><b>Publish:</b> Delegates to {@link RuleCompiler#publishSnapshot(Map)} for
 *       thread-safe atomic file writes.</li>
 * </ol>
 *
 * <p>This service is called synchronously after each flag mutation in
 * {@link com.flag.admin.service.FeatureFlagService}. No async messaging
 * or Redis round-trip is involved in the CDN publish path.</p>
 */
@Service
public class CdnSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(CdnSnapshotService.class);

    private final FeatureFlagRepository flagRepository;
    private final RuleCompiler ruleCompiler;

    public CdnSnapshotService(FeatureFlagRepository flagRepository,
                              RuleCompiler ruleCompiler) {
        this.flagRepository = flagRepository;
        this.ruleCompiler = ruleCompiler;
    }

    /**
     * Publish a CDN snapshot of all client-safe flags across all apps.
     *
     * <p>Steps:
     * <ol>
     *   <li>SQL: {@code SELECT * FROM flag_feature WHERE safe_for_client = TRUE}</li>
     *   <li>Convert each entity → FlagConfig → ClientFlagMetadata</li>
     *   <li>Write rules.<ver>.json + manifest.json atomically via RuleCompiler</li>
     * </ol>
     */
    public void publishAllSafeFlags() {
        List<FeatureFlagEntity> safeFlags = flagRepository.findBySafeForClientTrueAndEnabledTrue();

        if (safeFlags.isEmpty()) {
            log.info("No client-safe flags found, publishing empty CDN snapshot");
            ruleCompiler.publishSnapshot(Map.of());
            return;
        }

        Map<String, ClientFlagMetadata> clientFlags = new LinkedHashMap<>();
        for (FeatureFlagEntity entity : safeFlags) {
            FlagConfig config = RuleConfigParser.parse(
                    entity.getFlagKey(),
                    entity.getEnabled(),
                    entity.getRuleConfig()
            );
            clientFlags.put(entity.getFlagKey(), ClientFlagMetadata.from(config));
        }

        ruleCompiler.publishSnapshot(clientFlags);
        log.info("CDN snapshot published: {} client-safe flags written", clientFlags.size());
    }

    /**
     * Publish a CDN snapshot of client-safe flags for a specific app.
     *
     * <p>Same as {@link #publishAllSafeFlags()} but scoped to a single app.</p>
     */
    public void publishSafeFlagsForApp(String appId) {
        List<FeatureFlagEntity> safeFlags = flagRepository.findByAppIdAndSafeForClientTrueAndEnabledTrue(appId);

        if (safeFlags.isEmpty()) {
            log.debug("No client-safe flags found for appId={}, skipping CDN snapshot", appId);
            return;
        }

        // We still publish ALL safe flags to keep the snapshot complete across apps
        publishAllSafeFlags();
    }
}