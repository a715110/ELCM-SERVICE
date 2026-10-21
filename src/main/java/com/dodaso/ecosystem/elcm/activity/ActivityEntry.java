package com.dodaso.ecosystem.elcm.activity;

/**
 * ADDED 2026-10-20 -- one line of a package's Activity Log. occurredAt is UTC, ISO, like the other
 * times elcm-service sends. actorName is the display name when it can be found, else the login id.
 */
public record ActivityEntry(Long id, String occurredAt, String actor, String actorName,
                            String event, String message) {}
