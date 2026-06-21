package com.flag.eval.config;

import com.flag.eval.cache.FlagCache;
import com.flag.eval.listener.FlagDbLoader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Loads rules into memory cache when EvalService starts.
 *
 * Architecture diagram: EvalService starts -> full pull of DB rules -> pure in-memory hosting
 */
@Configuration
public class EvalServiceConfig {

    private static final Logger log = LoggerFactory.getLogger(EvalServiceConfig.class);

    private final FlagDbLoader flagDbLoader;
    private final FlagCache flagCache;

    public EvalServiceConfig(FlagDbLoader flagDbLoader, FlagCache flagCache) {
        this.flagDbLoader = flagDbLoader;
        this.flagCache = flagCache;
    }

    @PostConstruct
    public void loadAllRules() {
        log.info("EvalService starting: loading all rules from database into memory cache...");
        var allFlags = flagDbLoader.loadAll();
        allFlags.forEach(flagCache::putAll);
        log.info("EvalService initialized: {} apps loaded into memory", allFlags.size());
    }
}