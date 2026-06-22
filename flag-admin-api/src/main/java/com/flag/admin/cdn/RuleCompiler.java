package com.flag.admin.cdn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compiles server-side flag rules into versioned, client-safe JSON snapshots
 * and publishes them to the CDN root directory.
 *
 * <h3>Data Pruning (Security)</h3>
 * Only flags with {@code safe_for_client = true} in the database are included.
 * The SQL query-level filtering happens upstream — this class receives
 * only pre-filtered "safe" flags from {@link CdnSnapshotService}.
 *
 * <h3>Atomic File Publish</h3>
 * 1. Write rules.&#64;{@link #versionCounter}.json to a temp file in the same directory.
 * 2. Use {@link FileChannel#tryLock()} on manifest.json to serialize concurrent writes.
 * 3. Atomically rename the temp file, then overwrite manifest.json.
 *
 * <h3>Thread Safety</h3>
 * File I/O uses OS-level file locking via {@link FileChannel#lock()} to prevent
 * partial writes when multiple admin nodes publish simultaneously.
 */
@Component
public class RuleCompiler {

    private static final Logger log = LoggerFactory.getLogger(RuleCompiler.class);
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String RULES_PREFIX = "rules.";
    private static final String RULES_SUFFIX = ".json";

    private final AtomicInteger versionCounter = new AtomicInteger(1);
    private final ObjectMapper mapper;
    private final Path cdnRoot;

    public RuleCompiler(
            @Value("${cdn.root:/cdn_root}") String cdnRootPath,
            ObjectMapper mapper) {
        this.cdnRoot = Paths.get(cdnRootPath);
        this.mapper = mapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT);

        // Ensure CDN root directory exists
        try {
            Files.createDirectories(this.cdnRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create CDN root: " + cdnRootPath, e);
        }

        log.info("RuleCompiler initialized, CDN root: {}", cdnRoot.toAbsolutePath());
    }

    /**
     * Get the current CDN root path (for testing verification).
     */
    Path getCdnRoot() {
        return cdnRoot;
    }

    /**
     * Get the current version counter value (for testing verification).
     */
    int getCurrentVersion() {
        return versionCounter.get() - 1;
    }

    // ========================================================================
    //  Publish — atomic file write with POSIX-style temp + rename
    // ========================================================================

    /**
     * Publish versioned rules JSON + manifest atomically.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Increment version counter</li>
     *   <li>Serialise clientFlags to JSON string</li>
     *   <li>Write to {@code rules.<version>.json.tmp} in CDN root</li>
     *   <li>Acquire file lock on manifest.json</li>
     *   <li>Rename .tmp → .json (atomic on same filesystem)</li>
     *   <li>Overwrite manifest.json with new pointer</li>
     *   <li>Release lock</li>
     * </ol>
     *
     * @param clientFlags pre-filtered client-safe flags (no server-only flags reach here)
     */
    void publishSnapshot(Map<String, ClientFlagMetadata> clientFlags) {
        int version = versionCounter.getAndIncrement();
        String rulesFileName = RULES_PREFIX + version + RULES_SUFFIX;
        Path rulesFile = cdnRoot.resolve(rulesFileName);
        Path tmpFile = cdnRoot.resolve(rulesFileName + ".tmp");

        try {
            // ---- Build the JSON payload ----
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", version);
            payload.put("flags", clientFlags);
            String json = mapper.writeValueAsString(payload);

            // ---- Write to temp file first ----
            Files.writeString(tmpFile, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // ---- Acquire file lock on manifest to serialise concurrent writes ----
            Path manifestPath = cdnRoot.resolve(MANIFEST_FILE);
            if (!Files.exists(manifestPath)) {
                Files.writeString(manifestPath, "{}");
            }

            try (FileChannel channel = FileChannel.open(manifestPath,
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                    StandardOpenOption.SYNC)) {

                FileLock lock = null;
                int retries = 0;
                while (lock == null && retries < 100) {
                    try {
                        lock = channel.tryLock();
                    } catch (OverlappingFileLockException e) {
                        // Another thread/process holds the lock — spin briefly
                        Thread.sleep(10);
                        retries++;
                    }
                }
                if (lock == null) {
                    throw new IOException("Could not acquire file lock on manifest.json after " + retries + " retries");
                }

                try {
                    // ---- Atomically rename tmp → actual file ----
                    Files.move(tmpFile, rulesFile, StandardCopyOption.ATOMIC_MOVE);

                    // ---- Overwrite manifest with new pointer ----
                    Map<String, Object> manifest = new LinkedHashMap<>();
                    manifest.put("latest_file", rulesFileName);
                    manifest.put("version", version);
                    manifest.put("updated_at", java.time.Instant.now().toString());

                    // Clear the file and write
                    channel.position(0);
                    channel.truncate(0);
                    Files.writeString(manifestPath, mapper.writeValueAsString(manifest),
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

                } finally {
                    lock.release();
                }
            }

            log.info("Published CDN snapshot: rulesFile={}, flagsCount={}", rulesFileName, clientFlags.size());

        } catch (IOException e) {
            log.error("Failed to publish CDN snapshot: {}", e.getMessage(), e);
            // Clean up temp file if it still exists
            try {
                Files.deleteIfExists(tmpFile);
            } catch (IOException ignored) {
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while acquiring file lock");
        }
    }
}