package com.flag.common.constant;

/**
 * Constants for Redis Pub/Sub channels.
 *
 * Architecture (see basic_architecture_design.md):
 * Control plane AdminAPI publishes changes -> Redis Pub/Sub notification bus
 * -> Data plane EvalService listens and refreshes local memory cache
 */
public final class RedisChannels {

    /** Feature flag change notification channel */
    public static final String FLAG_CHANGE = "flag:change";

    /** App creation/deletion notification channel */
    public static final String APP_CHANGE = "app:change";

    /** Rule full-reload signal channel */
    public static final String FLAG_RELOAD = "flag:reload";

    private RedisChannels() {}
}