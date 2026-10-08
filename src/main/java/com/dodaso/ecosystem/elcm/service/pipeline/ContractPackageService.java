package com.dodaso.ecosystem.elcm.service.pipeline;

import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import com.dodaso.ecosystem.common.dto.FileUploadDTO;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Business logic for contract packages (FC-1 Pipeline), the public face used by the controller.
 *
 * REVISED 2026-08-19: backed by real repositories now.
 *
 * REVISED 2026-10-07 -- split in two, like StageDocumentService's two phases. The database work
 * lives in ContractPackageDataService (transactional, no outbound calls). This class calls it,
 * then does the two outbound lookups, the assignee's display name from IAMS and the file names
 * from common-service, outside any transaction. This class is deliberately not transactional.
 *
 * The package assignee is now a real column (contract_package.assignee_id, an IAMS login id),
 * replacing the earlier stand-in of created_by. It is shown as the resolved display name, falling
 * back to the stored value, or "Unassigned".
 *
 * "roles" (e.g. "1/1 roles") is a real computed value, not a stored column -- counts how many of
 * the package's documents have an actual role assigned (not UNDEFINED), matching the SDS 4.1
 * submission validation rule.
 */
@Service
@RequiredArgsConstructor
public class ContractPackageService {

    private static final String UNASSIGNED = "Unassigned";
    private static final String FILE_UNAVAILABLE = "(file unavailable)";

    private final ContractPackageDataService dataService;
    private final FileUploadLookupService fileUploadLookupService;
    private final AssigneeDirectoryLookupService assigneeDirectoryLookupService;

    public List<ContractPackageRow> findPackages() {
        final List<ContractPackageRow> rows = new ArrayList<>();
        for (final ContractPackageDraft d : dataService.loadPackages()) {
            final String assignee = displayNameOf(d.getAssigneeLoginId());
            rows.add(new ContractPackageRow(
                d.getId(),
                d.getPackageCode(),
                d.getDocCount(),
                d.getTargetRecord(),
                d.getWorkspace(),
                assignee,
                d.getAssigneeLoginId(),
                d.getRolesAssigned() + "/" + d.getDocCount() + " roles",
                d.getStatus(),
                d.getStatusCode()));
        }
        return rows;
    }

    public List<PackageDocumentRow> findPackageDocuments(final Long packageId) {
        final List<PackageDocumentDraft> drafts = dataService.loadPackageDocuments(packageId);
        final Set<Long> fileUploadIds = new HashSet<>();
        for (final PackageDocumentDraft d : drafts) {
            if (d.getFileUploadId() != null) {
                fileUploadIds.add(d.getFileUploadId());
            }
        }
        final Map<Long, FileUploadDTO> files = fileUploadLookupService.findByIds(fileUploadIds);
        final List<PackageDocumentRow> rows = new ArrayList<>();
        for (final PackageDocumentDraft d : drafts) {
            final FileUploadDTO file = d.getFileUploadId() != null ? files.get(d.getFileUploadId()) : null;
            // Keep the row even when the file name cannot be resolved, so the document can still
            // be removed from the package.
            rows.add(new PackageDocumentRow(d.getStagedDocumentId(),
                file != null && file.getFileName() != null ? file.getFileName() : FILE_UNAVAILABLE,
                d.getRoleCode(), d.getRoleLabel(),
                d.getTargetRecord() != null ? d.getTargetRecord() : UNASSIGNED));
        }
        return rows;
    }

    public List<DocumentRoleOptionRow> findDocumentRoles() {
        return dataService.loadDocumentRoles();
    }

    public ContractPackageCreatedResponse createPackage(final CreateContractPackageRequest request) {
        return dataService.createPackage(request != null ? request.getDocuments() : null,
            request != null ? request.getAssigneeId() : null);
    }

    public void addDocuments(final Long packageId, final AddPackageDocumentsRequest request) {
        dataService.addDocuments(packageId, request != null ? request.getDocuments() : null);
    }

    public void removeDocument(final Long packageId, final Long stagedDocumentId) {
        dataService.removeDocument(packageId, stagedDocumentId);
    }

    public void reassign(final Long packageId, final ReassignPackageRequest request) {
        dataService.reassign(packageId, request != null ? request.getAssigneeId() : null);
    }

    private String displayNameOf(final String loginId) {
        if (loginId == null || loginId.isBlank()) {
            return UNASSIGNED;
        }
        final Optional<UserDirectoryDTO> entry = assigneeDirectoryLookupService.findByAssigneeId(loginId);
        if (entry.isPresent() && entry.get().getDisplayName() != null && !entry.get().getDisplayName().isBlank()) {
            return entry.get().getDisplayName();
        }
        return loginId;
    }
}
