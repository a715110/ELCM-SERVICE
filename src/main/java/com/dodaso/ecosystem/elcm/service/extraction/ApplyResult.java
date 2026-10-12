package com.dodaso.ecosystem.elcm.service.extraction;

import java.util.List;

/**
 * What Apply did: terms written (new or changed), terms left alone because the record already
 * held the same value, fields skipped (no value, or a value that does not fit its type), and what
 * happened to the counterparty (lessor): NONE, CREATED, LINKED or UNCHANGED.
 */
public record ApplyResult(int written, int unchanged, List<String> skipped, String counterparty,
                          String counterpartyAction, String recordCode) {
}
