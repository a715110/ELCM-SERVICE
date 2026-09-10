package com.dodaso.ecosystem.elcm.service.pipeline;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Row shape returned by ContractPackageService. See StagedDocumentRow's
 * class-level note for why this is a plain class rather than a record.
 *
 * REVISED 2026-08-19: batchId REMOVED. The original mock version had a
 * "BATCH--LOCAL"-style value here, but nothing in the real elcm.
 * contract_package table (or anywhere else in the schema) backs a "batch"
 * concept at all -- it was demo-screenshot flavor with no real column.
 * Rather than hardcode a fake value for every row, the field is gone.
 * dashboard.xhtml's "Batch ID" column needs to be removed to match --
 * flagged in chat, not done here since that's a UI change, not a data-layer
 * one.
 */
@Getter
@AllArgsConstructor
public class ContractPackageRow implements Serializable {
    private final String packageCode;
    private final int docCount;
    private final String targetRecord;
    private final String workspace;
    private final String assignee;
    private final String roles;
    private final String status;
}
