package com.dodaso.ecosystem.elcm.service.extraction;

/** Body of the approver's send-back: why the review goes back to the reviewer. */
public record SendBackRequest(String reason) {}
