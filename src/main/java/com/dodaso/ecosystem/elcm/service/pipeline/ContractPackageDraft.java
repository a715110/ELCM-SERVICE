package com.dodaso.ecosystem.elcm.service.pipeline;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Everything ContractPackageService needs from a ContractPackage, read inside the transaction of
 * ContractPackageDataService so no lazy association is touched afterwards. The assignee display
 * name is resolved later, outside any transaction, because that is an outbound call to IAMS.
 */
@Getter
@AllArgsConstructor
public class ContractPackageDraft {
    private final Long id;
    private final String packageCode;
    private final int docCount;
    private final String targetRecord;
    private final String workspace;
    private final String assigneeLoginId;
    private final long rolesAssigned;
    private final String status;
    private final String statusCode;
}
