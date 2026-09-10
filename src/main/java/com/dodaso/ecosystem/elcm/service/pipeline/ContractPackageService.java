package com.dodaso.ecosystem.elcm.service.pipeline;

import com.dodaso.ecosystem.elcm.entity.pipeline.ContractPackage;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractPackageRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.PackageDocumentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic + data access for contract packages (FC-1 Pipeline).
 *
 * REVISED 2026-08-19: backed by real repositories now. See
 * StageDocumentService's class-level note on @Transactional(readOnly=true)
 * being required, not optional, given open-in-view=false and lazy
 * relationships.
 *
 * ONE OPEN QUESTION, not resolved here: "assignee" has no dedicated column
 * on contract_package at all (unlike staged_document, which has a real
 * assignee_id). created_by is used as an approximate stand-in below --
 * flagged clearly in chat as a real product question (does "package
 * assignee" mean whoever created it, whoever currently holds it via
 * submission.claimed_by, something else?), not something I decided
 * unilaterally.
 *
 * "roles" (e.g. "1/1 roles") is a real computed value, not a stored column
 * -- counts how many of the package's documents have an actual role
 * assigned (not UNDEFINED), matching the SDS 4.1 submission validation
 * rule.
 */
@Service
@RequiredArgsConstructor
public class ContractPackageService {

    private static final String UNDEFINED_ROLE_CODE = "UNDEFINED";

    private final ContractPackageRepository contractPackageRepository;
    private final PackageDocumentRepository packageDocumentRepository;

    @Transactional(readOnly = true)
    public List<ContractPackageRow> findPackages() {
        return contractPackageRepository.findAll().stream()
            .map(this::mapToRow)
            .toList();
    }

    private ContractPackageRow mapToRow(ContractPackage pkg) {
        long totalDocs = packageDocumentRepository.countByContractPackage_Id(pkg.getId());
        long rolesAssigned = packageDocumentRepository
            .countByContractPackage_IdAndDocumentRole_CodeNot(pkg.getId(), UNDEFINED_ROLE_CODE);

        String targetRecord = pkg.getTargetRecord() != null
            ? pkg.getTargetRecord().getRecordCode()
            : "Unassigned";
        // See class-level note -- created_by is an approximation, not a
        // confirmed real answer to "who is this package assigned to".
        String assignee = pkg.getCreatedBy() != null ? pkg.getCreatedBy() : "Unassigned";
        String roles = rolesAssigned + "/" + totalDocs + " roles";

        return new ContractPackageRow(
            pkg.getPackageCode(),
            (int) totalDocs,
            targetRecord,
            pkg.getWorkspace().getCode(),
            assignee,
            roles,
            pkg.getStatus().getLabel()
        );
    }
}