package com.dodaso.ecosystem.elcm.service.extraction;

/** Body of the reviewer's save: the corrected value (blank clears it) and whether the field is confirmed. */
public record SaveFieldRequest(String correctedValue, Boolean verified) {
}
