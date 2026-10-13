package com.dodaso.ecosystem.elcm.service.contract;

/**
 * One applied value of a contract record. value is the display text of the typed value. current is
 * false for a value a later Apply replaced (supersededAt says when). Times are ISO text (UTC).
 */
public record ContractTermView(Long id, String fieldKey, String label, String dataType, String value,
                               boolean current, String packageCode, Long submissionId, Integer revisionNo,
                               String appliedBy, String appliedAt, String supersededAt) {
}
