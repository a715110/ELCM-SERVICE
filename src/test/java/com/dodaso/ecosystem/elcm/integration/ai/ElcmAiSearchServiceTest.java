package com.dodaso.ecosystem.elcm.integration.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.ai.container.AiIndexDTOContainer;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.elcm.dto.WorkspaceDTO;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractRecord;
import com.dodaso.ecosystem.elcm.entity.pipeline.StagedDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.Workspace;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractRecordRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.PackageDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.StagedDocumentRepository;
import com.dodaso.ecosystem.elcm.service.pipeline.WorkspaceService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

/** The caller's workspaces decide the scope; hits outside it, or deleted since, are left out. */
class ElcmAiSearchServiceTest {

    private RESTServiceClient rest;
    private StagedDocumentRepository docs;
    private ContractRecordRepository records;
    private ElcmAiSearchService service;

    @BeforeEach
    void setUp() throws Exception {
        rest = mock(RESTServiceClient.class);
        docs = mock(StagedDocumentRepository.class);
        records = mock(ContractRecordRepository.class);
        WorkspaceService workspaces = mock(WorkspaceService.class);
        when(workspaces.getAllWorkspaces()).thenReturn(List.of(ws(3L, true), ws(7L, true), ws(9L, false)));
        service = new ElcmAiSearchService(rest, workspaces, docs, records, mock(PackageDocumentRepository.class),
            mock(PlatformTransactionManager.class));
        when(rest.post(anyString(), anyString(), any(), any())).thenReturn(new AiIndexDTOContainer());
    }

    private static WorkspaceDTO ws(Long id, boolean active) {
        WorkspaceDTO w = new WorkspaceDTO();
        w.setId(id);
        w.setIsActive(active);
        return w;
    }

    private AiIndexDTOContainer sentBody() throws Exception {
        ArgumentCaptor<AiIndexDTOContainer> body = ArgumentCaptor.forClass(AiIndexDTOContainer.class);
        verify(rest).post(anyString(), eq(ElcmAiSearchService.SEARCH_PATH), body.capture(), any());
        return body.getValue();
    }

    private static AiSearchResultDTO hit(String type, long id, Long scope) {
        return AiSearchResultDTO.builder().appCode("ELCM").sourceType(type).sourceId(id).scopeId(scope)
            .title("lease.pdf").chunkText("Section 7 Rent  The rent is $4,000.").score(0.81).build();
    }

    @Test
    void searchesEveryActiveWorkspaceOfTheCaller() throws Exception {
        service.search(new ElcmAiSearchRequest("rent increase", null, null, null), "kim");

        AiIndexDTOContainer body = sentBody();
        assertEquals("ELCM", body.getAiScopedSearchDTO().getAppCode());
        assertEquals(List.of(3L, 7L), body.getAiScopedSearchDTO().getScopeIds());
        assertEquals("kim", body.getAiScopedSearchDTO().getUserRef());
    }

    @Test
    void oneWorkspaceNarrowsTheSearch() throws Exception {
        service.search(new ElcmAiSearchRequest("rent", 7L, null, 5), "kim");
        assertEquals(List.of(7L), sentBody().getAiScopedSearchDTO().getScopeIds());
    }

    @Test
    void workspaceTheCallerCantSeeIsForbiddenAndNotSent() throws Exception {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> service.search(new ElcmAiSearchRequest("rent", 9L, null, null), "kim"));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(rest, never()).post(anyString(), anyString(), any(), any());
    }

    @Test
    void emptySearchIs400() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> service.search(new ElcmAiSearchRequest("  ", null, null, null), "kim"));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void aiDownIs503() throws Exception {
        when(rest.post(anyString(), anyString(), any(), any())).thenThrow(new RuntimeException("refused"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> service.search(new ElcmAiSearchRequest("rent", null, null, null), "kim"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
    }

    @Test
    void hitsOutsideTheScopeOrDeletedAreLeftOutAndLinksFilled() {
        Workspace retail = new Workspace();
        retail.setId(3L);
        retail.setName("Retail");
        StagedDocument live = new StagedDocument();
        live.setId(55L);
        live.setWorkspace(retail);
        live.setFileUploadId(900L);
        StagedDocument deleted = new StagedDocument();
        deleted.setId(56L);
        deleted.setWorkspace(retail);
        deleted.setDeletedAt(LocalDateTime.now());
        when(docs.findById(55L)).thenReturn(Optional.of(live));
        when(docs.findById(56L)).thenReturn(Optional.of(deleted));
        ContractRecord record = new ContractRecord();
        record.setId(41L);
        record.setRecordCode("LEASE-0041");
        record.setWorkspace(retail);
        when(records.findById(41L)).thenReturn(Optional.of(record));

        List<ElcmAiSearchResult> results = service.describe(List.of(
            hit("elcm_staged_document", 55, 3L),
            hit("elcm_staged_document", 56, 3L),      // deleted since it was indexed
            hit("elcm_staged_document", 57, 8L),      // other workspace
            hit("elcm_contract_record", 41, 3L)), List.of(3L, 7L));

        assertEquals(2, results.size());
        ElcmAiSearchResult doc = results.get(0);
        assertEquals(55L, doc.sourceId());
        assertEquals(900L, doc.fileUploadId());
        assertEquals("lease.pdf", doc.fileName());
        assertEquals("Retail", doc.workspaceName());
        assertEquals("Section 7 Rent The rent is $4,000.", doc.snippet());
        assertEquals("LEASE-0041", results.get(1).recordCode());
        assertTrue(results.stream().noneMatch(r -> r.sourceId() == 57L));
    }
}
