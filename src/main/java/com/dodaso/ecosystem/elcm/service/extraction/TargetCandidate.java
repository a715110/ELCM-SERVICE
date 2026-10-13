package com.dodaso.ecosystem.elcm.service.extraction;

/** A contract record a package without a target can be applied to. */
public record TargetCandidate(Long id, String recordCode, String contractType, String counterparty, String status) {
}
