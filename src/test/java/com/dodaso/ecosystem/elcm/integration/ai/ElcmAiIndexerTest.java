package com.dodaso.ecosystem.elcm.integration.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.ai.container.AiIndexDTOContainer;
import com.dodaso.ecosystem.ai.dto.AiIndexDTO;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpContractRecordStatus;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpContractType;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractPackage;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractRecord;
import com.dodaso.ecosystem.elcm.entity.pipeline.Counterparty;
import com.dodaso.ecosystem.elcm.entity.pipeline.PackageDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.StagedDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.Workspace;
import com.dodaso.ecosystem.elcm.entity.property.Address;
import com.dodaso.ecosystem.elcm.entity.property.Property;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractRecordRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.PackageDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.StagedDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.property.PropertyRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/** ELCM documents and records are sent to AI search with their workspace and owner. */
class ElcmAiIndexerTest {

    private RESTServiceClient rest;
    private StagedDocumentRepository docs;
    private ContractRecordRepository records;
    private PackageDocumentRepository packageDocs;
    private PropertyRepository properties;
    private ElcmAiIndexer indexer;

    @BeforeEach
    void setUp() {
        rest = mock(RESTServiceClient.class);
        docs = mock(StagedDocumentRepository.class);
        records = mock(ContractRecordRepository.class);
        packageDocs = mock(PackageDocumentRepository.class);
        properties = mock(PropertyRepository.class);
        indexer = new ElcmAiIndexer(rest, docs, records, packageDocs, properties,
            mock(PlatformTransactionManager.class), true);
    }

    private static Workspace workspace(long id) {
        Workspace w = new Workspace();
        w.setId(id);
        w.setName("Retail");
        return w;
    }

    private static ContractRecord record(long id) {
        ContractRecord r = new ContractRecord();
        r.setId(id);
        r.setRecordCode("LEASE-0041");
        r.setWorkspace(workspace(3));
        return r;
    }

    private StagedDocument doc(long id) {
        StagedDocument d = new StagedDocument();
        d.setId(id);
        d.setWorkspace(workspace(3));
        when(docs.findById(id)).thenReturn(Optional.of(d));
        return d;
    }

    @Test
    void documentIsIndexedInItsWorkspaceWithItsFile() {
        doc(55);
        byte[] bytes = {1, 2, 3};

        AiIndexDTO dto = indexer.stagedDocumentIndex(55L, "lease.pdf", bytes);

        assertEquals("ELCM", dto.getAppCode());
        assertEquals(3L, dto.getScopeId());
        assertEquals("elcm_staged_document", dto.getSourceType());
        assertEquals(55L, dto.getSourceId());
        assertEquals("staged_document", dto.getOwnerType());
        assertEquals(55L, dto.getOwnerId());
        assertEquals("lease.pdf", dto.getFileName());
        assertArrayEquals(bytes, dto.getFileBytes());
    }

    @Test
    void documentOwnerIsItsTargetRecordElseItsPackagesRecord() {
        doc(55).setTargetRecord(record(41));
        assertEquals("contract_record", indexer.stagedDocumentIndex(55L, "a.pdf", new byte[] {1}).getOwnerType());
        assertEquals(41L, indexer.stagedDocumentIndex(55L, "a.pdf", new byte[] {1}).getOwnerId());

        doc(56);
        ContractPackage pkg = new ContractPackage();
        pkg.setTargetRecord(record(42));
        PackageDocument link = new PackageDocument();
        link.setContractPackage(pkg);
        when(packageDocs.findByStagedDocument_IdIn(List.of(56L))).thenReturn(List.of(link));
        assertEquals(42L, indexer.stagedDocumentIndex(56L, "b.pdf", new byte[] {1}).getOwnerId());
    }

    @Test
    void deletedOrEmptyDocumentIsNotIndexed() {
        doc(57).setDeletedAt(LocalDateTime.now());
        assertNull(indexer.stagedDocumentIndex(57L, "c.pdf", new byte[] {1}));
        doc(58);
        assertNull(indexer.stagedDocumentIndex(58L, "d.pdf", new byte[0]));
        assertNull(indexer.stagedDocumentIndex(99L, "missing.pdf", new byte[] {1}));
    }

    @Test
    void recordTextHasTypeCounterpartyPropertyAndStatus() {
        ContractRecord r = record(41);
        LkpContractType type = new LkpContractType();
        type.setLabel("Property Lease");
        r.setContractType(type);
        Counterparty cp = new Counterparty();
        cp.setName("Acme Holdings");
        r.setCounterparty(cp);
        LkpContractRecordStatus status = new LkpContractRecordStatus();
        status.setLabel("Active");
        r.setStatus(status);
        when(records.findById(41L)).thenReturn(Optional.of(r));
        Address a = new Address();
        a.setAddressLine1("123 Main St");
        a.setCity("Springfield");
        Property p = new Property();
        p.setAddress(a);
        when(properties.findByContractRecord_Id(41L)).thenReturn(Optional.of(p));

        AiIndexDTO dto = indexer.contractRecordIndex(41L);

        assertEquals("elcm_contract_record", dto.getSourceType());
        assertEquals(3L, dto.getScopeId());
        assertEquals("LEASE-0041", dto.getTitle());
        assertEquals("Contract record LEASE-0041 | Type: Property Lease | Counterparty: Acme Holdings"
            + " | Property: 123 Main St, Springfield | Status: Active | Workspace: Retail", dto.getText());
    }

    @Test
    void removedDocumentsAreDeletedInElcmOnly() throws Exception {
        indexer.stagedDocumentsRemoved(List.of(55L, 55L, 56L));

        ArgumentCaptor<AiIndexDTOContainer> body = ArgumentCaptor.forClass(AiIndexDTOContainer.class);
        verify(rest, timeout(2000)).post(anyString(), eq(ElcmAiIndexer.DELETE_PATH), body.capture(), any());
        assertEquals("ELCM", body.getValue().getAiIndexDeleteDTO().getAppCode());
        assertEquals("elcm_staged_document", body.getValue().getAiIndexDeleteDTO().getSourceType());
        assertEquals(List.of(55L, 56L), body.getValue().getAiIndexDeleteDTO().getSourceIds());
    }

    @Test
    void aiFailureIsSwallowedAndDisabledSendsNothing() throws Exception {
        when(rest.post(anyString(), anyString(), any(), any())).thenThrow(new RuntimeException("ai down"));
        indexer.stagedDocumentsRemoved(List.of(55L));   // must not throw
        verify(rest, timeout(2000)).post(anyString(), anyString(), any(), any());

        RESTServiceClient quiet = mock(RESTServiceClient.class);
        ElcmAiIndexer off = new ElcmAiIndexer(quiet, docs, records, packageDocs, properties,
            mock(PlatformTransactionManager.class), false);
        off.stagedDocumentsRemoved(List.of(55L));
        verify(quiet, after(300).never()).post(anyString(), anyString(), any(), any());
    }
}
