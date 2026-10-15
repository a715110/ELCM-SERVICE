package com.dodaso.ecosystem.elcm.service.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * elcm.extraction.* settings. Defaults suit the CPU-only pilot: documents take one to several
 * minutes each, so the request timeout is long and the worker handles one document at a time.
 */
@ConfigurationProperties(prefix = "elcm.extraction")
public class ExtractionProperties {

    /** Master switch. When false nothing is picked up and submissions stay PENDING. */
    private boolean enabled = false;

    /** Base URL of the extractor service, e.g. http://localhost:8088 . */
    private String baseUrl = "http://localhost:8088";

    /**
     * REVISED 2026-10-15 -- how often the worker looks for a PENDING submission when nobody woke it.
     * Submit, retry and read again wake it at once, so this is only the safety net (and how other
     * service instances notice work).
     */
    private long pollIntervalMs = 60_000;

    /**
     * ADDED 2026-10-15 -- how often the worker checks for stale PROCESSING work. This does not need
     * the poll rhythm: stale means no heartbeat for staleMinutes, so every few minutes is plenty.
     */
    private long recoverIntervalMs = 300_000;

    /** How long to wait for one document's extraction. */
    private int requestTimeoutSeconds = 900;

    /** How many times one document is tried before it is marked FAILED. */
    private int maxAttempts = 2;

    /** Pause between attempts of the same document. */
    private long retryDelayMs = 15_000;

    /**
     * A PROCESSING submission whose heartbeat is older than this is assumed to belong to a
     * worker that died and goes back to PENDING. Must be longer than the longest document.
     */
    private int staleMinutes = 45;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(final String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(final long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public long getRecoverIntervalMs() {
        return recoverIntervalMs;
    }

    public void setRecoverIntervalMs(final long recoverIntervalMs) {
        this.recoverIntervalMs = recoverIntervalMs;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(final int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(final int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(final long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    public int getStaleMinutes() {
        return staleMinutes;
    }

    public void setStaleMinutes(final int staleMinutes) {
        this.staleMinutes = staleMinutes;
    }
}
