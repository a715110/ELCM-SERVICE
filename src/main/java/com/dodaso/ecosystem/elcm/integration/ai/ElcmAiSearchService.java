package com.dodaso.ecosystem.elcm.integration.ai;

import com.dodaso.ecosystem.ai.constant.AppCodeEnum;
import com.dodaso.ecosystem.ai.constant.ElcmSourceTypeEnum;
import com.dodaso.ecosystem.ai.container.AiIndexDTOContainer;
import com.dodaso.ecosystem.ai.dto.AiScopedSearchDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.baseline.common.constant.ServiceDiscoveryEnum;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.elcm.dto.WorkspaceDTO;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractPackage;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractRecord;
import com.dodaso.ecosystem.elcm.entity.pipeline.PackageDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.StagedDocument;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractRecordRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.PackageDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.StagedDocumentRepository;
import com.dodaso.ecosystem.elcm.service.pipeline.WorkspaceService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI search over ELCM content. ELCM-SERVICE decides which workspaces the caller may see and
 * sends only those to ai-search-service; the browser never chooses the scope list. Hits are
 * checked against the ELCM database (a deleted document is left out) and filled with what
 * documentviewer.xhtml needs.
 */
@Service
@Slf4j
public class ElcmAiSearchService {

    static final String SEARCH_PATH = "/aiSearchController/searchScoped";
    static final int MAX_QUERY_CHARS = 500;
    private static final int SNIPPET_CHARS = 300;

    private final RESTServiceClient restServiceClient;
    private final WorkspaceService workspaceService;
    private final StagedDocumentRepository stagedDocumentRepository;
    private final ContractRecordRepository contractRecordRepository;
    private final PackageDocumentRepository packageDocumentRepository;
    private final TransactionTemplate readOnly;

    public ElcmAiSearchService(final RESTServiceClient restServiceClient, final WorkspaceService workspaceService,
                               final StagedDocumentRepository stagedDocumentRepository,
                               final ContractRecordRepository contractRecordRepository,
                               final PackageDocumentRepository packageDocumentRepository,
                               final PlatformTransactionManager transactionManager) {
        this.restServiceClient = restServiceClient;
        this.workspaceService = workspaceService;
        this.stagedDocumentRepository = stagedDocumentRepository;
        this.contractRecordRepository = contractRecordRepository;
        this.packageDocumentRepository = packageDocumentRepository;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * Workspaces the caller may search. ELCM has no per-user workspace access today: everyone with
     * pipeline:view (checked on the endpoint) sees every active workspace, as on the other ELCM
     * screens. Kept in this one method so a per-user rule can be added here.
     */
    List<Long> allowedWorkspaceIds(final String caller) {
        return workspaceService.getAllWorkspaces().stream()
            .filter(w -> Boolean.TRUE.equals(w.getIsActive()))
            .map(WorkspaceDTO::getId)
            .filter(Objects::nonNull)
            .toList();
    }

    public List<ElcmAiSearchResult> search(final ElcmAiSearchRequest request, final String caller) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter something to search for");
        }
        if (request.query().length() > MAX_QUERY_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "The search is longer than " + MAX_QUERY_CHARS + " characters");
        }
        final List<Long> allowed = allowedWorkspaceIds(caller);
        final List<Long> scopes;
        if (request.workspaceId() == null) {
            scopes = allowed;
        } else if (allowed.contains(request.workspaceId())) {
            scopes = List.of(request.workspaceId());
        } else {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can't search that workspace");
        }
        if (scopes.isEmpty()) {
            return List.of();
        }

        final AiIndexDTOContainer body = new AiIndexDTOContainer();
        body.setAiScopedSearchDTO(AiScopedSearchDTO.builder()
            .appCode(AppCodeEnum.ELCM.name())
            .scopeIds(scopes)
            .sourceTypes(request.sourceTypes())
            .query(request.query().strip())
            .limit(request.limit())
            .userRef(caller)
            .build());
        final AiIndexDTOContainer answer;
        try {
            answer = restServiceClient.post(ServiceDiscoveryEnum.ai_service.getServiceDiscoveryName(),
                SEARCH_PATH, body, new ParameterizedTypeReference<AiIndexDTOContainer>() {
                });
        } catch (final Exception e) {
            log.warn("AI-SEARCH failed for {}: {}", caller, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI search is not available right now");
        }
        final List<AiSearchResultDTO> hits = answer == null || answer.getAiSearchResultDTOList() == null
            ? List.of() : answer.getAiSearchResultDTOList();
        return readOnly.execute(status -> describe(hits, scopes));
    }

    /** Hits still present in ELCM and inside the searched workspaces, with their link fields. */
    List<ElcmAiSearchResult> describe(final List<AiSearchResultDTO> hits, final List<Long> scopes) {
        final List<ElcmAiSearchResult> results = new ArrayList<>();
        for (final AiSearchResultDTO hit : hits) {
            if (hit.getScopeId() == null || !scopes.contains(hit.getScopeId()) || hit.getSourceId() == null) {
                continue;
            }
            final ElcmAiSearchResult result = describe(hit);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    private ElcmAiSearchResult describe(final AiSearchResultDTO hit) {
        if (ElcmSourceTypeEnum.STAGED_DOCUMENT.getSourceType().equals(hit.getSourceType())) {
            final StagedDocument doc = stagedDocumentRepository.findById(hit.getSourceId()).orElse(null);
            if (doc == null || doc.getDeletedAt() != null || doc.getWorkspace() == null) {
                return null;
            }
            final ContractRecord record = recordOf(doc);
            return new ElcmAiSearchResult(hit.getSourceType(), hit.getSourceId(), hit.getTitle(),
                snippet(hit.getChunkText()), hit.getScore(), doc.getWorkspace().getId(), doc.getWorkspace().getName(),
                record == null ? null : record.getId(), record == null ? null : record.getRecordCode(),
                doc.getFileUploadId(), hit.getTitle(),
                doc.getContractType() == null ? null : doc.getContractType().getLabel(),
                doc.getAssigneeId(), doc.getUploadedAt());
        }
        if (ElcmSourceTypeEnum.CONTRACT_RECORD.getSourceType().equals(hit.getSourceType())) {
            final ContractRecord record = contractRecordRepository.findById(hit.getSourceId()).orElse(null);
            if (record == null || record.getWorkspace() == null) {
                return null;
            }
            return new ElcmAiSearchResult(hit.getSourceType(), hit.getSourceId(), record.getRecordCode(),
                snippet(hit.getChunkText()), hit.getScore(), record.getWorkspace().getId(),
                record.getWorkspace().getName(), record.getId(), record.getRecordCode(), null, null,
                record.getContractType() == null ? null : record.getContractType().getLabel(), null, null);
        }
        return null;
    }

    /** The record a document belongs to now: its own target, else its package's. */
    private ContractRecord recordOf(final StagedDocument doc) {
        if (doc.getTargetRecord() != null) {
            return doc.getTargetRecord();
        }
        return packageDocumentRepository.findByStagedDocument_IdIn(List.of(doc.getId())).stream()
            .map(PackageDocument::getContractPackage)
            .filter(Objects::nonNull)
            .map(ContractPackage::getTargetRecord)
            .filter(Objects::nonNull)
            .findFirst().orElse(null);
    }

    static String snippet(final String text) {
        if (text == null) {
            return null;
        }
        final String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= SNIPPET_CHARS ? flat : flat.substring(0, SNIPPET_CHARS) + "...";
    }
}
