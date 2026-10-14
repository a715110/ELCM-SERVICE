package com.dodaso.ecosystem.elcm.service.extraction;

/**
 * Body of the reviewer's save: the corrected value (blank clears it), whether the field is
 * confirmed, and the reason for changing the value. The reason is required (10 to 500 characters)
 * only when the value changes; confirming without a change needs none.
 */
public record SaveFieldRequest(String correctedValue, Boolean verified, String reason) {}
