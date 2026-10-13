package com.dodaso.ecosystem.elcm.service.contract;

/** One contract record for the Contract Records list: who it is, and how many applied terms it has. */
public record ContractRecordSummary(Long id, String recordCode, String contractType, String counterparty,
                                    String workspace, String status, int termCount, String lastAppliedAt) {
}
