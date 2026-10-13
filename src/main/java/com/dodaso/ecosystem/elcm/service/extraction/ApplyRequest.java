package com.dodaso.ecosystem.elcm.service.extraction;

/** Body of Apply. targetRecordId is used only when the package has no target contract record yet. */
public record ApplyRequest(Long targetRecordId) {
}
