package com.dodaso.ecosystem.elcm.service.contract;

import java.util.List;

/** A contract record with its applied terms (current ones, plus replaced ones when asked for). */
public record ContractRecordTerms(ContractRecordSummary record, List<ContractTermView> terms) {
}
