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
 *
 * REVISED 2026-10-07 -- added id (the package's key, for the add and remove
 * calls), statusCode (the stable code the UI tests, for example ASSEMBLY, as
 * opposed to the display label in status), and assigneeLoginId (the stored
 * assignee, as opposed to assignee, which is the resolved display name).
 * Field order matches the @AllArgsConstructor call in ContractPackageService.
 */
@Getter
@AllArgsConstructor
public class ContractPackageRow implements Serializable {
    private final Long id;
    private final String packageCode;
    private final int docCount;
    private final String targetRecord;
    private final String workspace;
    private final String assignee;
    private final String assigneeLoginId;
    private final String roles;
    private final String status;
    private final String statusCode;
}
