package com.dodaso.ecosystem.elcm.service.extraction;

/** One send-back of a review: who, when (UTC, ISO) and why. Newest first in the list. */
public record SendBackEntry(Long id, String reason, String sentBy, String sentAt) {}
