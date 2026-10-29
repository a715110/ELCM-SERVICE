package com.dodaso.ecosystem.elcm.integration.ai;

import com.dodaso.ecosystem.ai.constant.AppCodeEnum;
import com.dodaso.ecosystem.ai.constant.ElcmSourceTypeEnum;
import com.dodaso.ecosystem.ai.container.AiIndexDTOContainer;
import com.dodaso.ecosystem.ai.dto.AiIndexDTO;
import com.dodaso.ecosystem.ai.dto.AiIndexDeleteDTO;
import com.dodaso.ecosystem.baseline.common.constant.ServiceDiscoveryEnum;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractRecord;
import com.dodaso.ecosystem.elcm.entity.pipeline.PackageDocument;
import com.dodaso.ecosystem.elcm.entity.pipeline.StagedDocument;
import com.dodaso.ecosystem.elcm.entity.property.Address;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractRecordRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.PackageDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.StagedDocumentRepository;
import com.dodaso.ecosystem.elcm.repository.property.PropertyRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends ELCM records to ai-search-service so they can be found by AI search: a staged document's
 * file once it has been extracted, and a contract record's details when it is created or its terms
 * are applied. Removes a staged document's rows when it is deleted or purged.
 *
 * <p>Fire-and-forget: the work runs on one background thread, after the caller's transaction has
 * committed, and every failure is logged, never thrown, so AI search can't fail an ELCM save. The
 * calls carry no user (they run off the request thread); ai-search-service accepts them as service
 * calls.
 */
@Component
@Slf4j
public class ElcmAiIndexer {

    static final String INDEX_PATH = "/aiIndexController/index";
    static final String DELETE_PATH = "/aiIndexController/delete";
    static final String OWNER_RECORD = "contract_record";
    static final String OWNER_DOCUMENT = "staged_document";

    private final RESTServiceClient restServiceClient;
    private final StagedDocumentRepository stagedDocumentRepository;
    private final ContractRecordRepository contractRecordRepository;
    private final PackageDocumentRepository packageDocumentRepository;
    private final PropertyRepository propertyRepository;
    private final TransactionTemplate readOnly;
    private final boolean enabled;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "elcm-ai-index");
        thread.setDaemon(true);
        return thread;
    });

    public ElcmAiIndexer(final RESTServiceClient restServiceClient,
                         final StagedDocumentRepository stagedDocumentRepository,
                         final ContractRecordRepository contractRecordRepository,
                         final PackageDocumentRepository packageDocumentRepository,
                         final PropertyRepository propertyRepository,
                         final PlatformTransactionManager transactionManager,
                         @Value("${dodaso.elcm.ai.index.enabled:true}") final boolean enabled) {
        this.restServiceClient = restServiceClient;
        this.stagedDocumentRepository = stagedDocumentRepository;
        this.contractRecordRepository = contractRecordRepository;
        this.packageDocumentRepository = packageDocumentRepository;
        this.propertyRepository = propertyRepository;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.enabled = enabled;
    }

    /** A staged document was extracted; its file is indexed. The bytes are the ones just extracted. */
    public void stagedDocumentExtracted(final Long stagedDocumentId, final String fileName, final byte[] content) {
        submitAfterCommit("staged document " + stagedDocumentId, () -> {
            final AiIndexDTO dto = readOnly.execute(status -> stagedDocumentIndex(stagedDocumentId, fileName, content));
            if (dto != null) {
                post(INDEX_PATH, container(dto, null));
            }
        });
    }

    /** A contract record was created or changed; its details are indexed. */
    public void contractRecordChanged(final Long recordId) {
        submitAfterCommit("contract record " + recordId, () -> {
            final AiIndexDTO dto = readOnly.execute(status -> contractRecordIndex(recordId));
            if (dto != null) {
                post(INDEX_PATH, container(dto, null));
            }
        });
    }

    /** Staged documents were deleted or purged; their rows are removed. */
    public void stagedDocumentsRemoved(final Collection<Long> stagedDocumentIds) {
        final List<Long> ids = stagedDocumentIds == null ? List.of()
            : stagedDocumentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        submitAfterCommit("removed staged documents " + ids, () -> post(DELETE_PATH, container(null,
            AiIndexDeleteDTO.builder().appCode(AppCodeEnum.ELCM.name())
                .sourceType(ElcmSourceTypeEnum.STAGED_DOCUMENT.getSourceType()).sourceIds(ids).build())));
    }

    AiIndexDTO stagedDocumentIndex(final Long stagedDocumentId, final String fileName, final byte[] content) {
        final StagedDocument doc = stagedDocumentRepository.findById(stagedDocumentId).orElse(null);
        if (doc == null || doc.getDeletedAt() != null || doc.getWorkspace() == null || content == null
                || content.length == 0) {
            log.info("AI-INDEX skipped staged document {}: missing, deleted, no workspace or no content",
                stagedDocumentId);
            return null;
        }
        final ContractRecord record = recordOf(doc);
        return AiIndexDTO.builder()
            .appCode(AppCodeEnum.ELCM.name())
            .scopeId(doc.getWorkspace().getId())
            .sourceType(ElcmSourceTypeEnum.STAGED_DOCUMENT.getSourceType())
            .sourceId(doc.getId())
            .ownerType(record != null ? OWNER_RECORD : OWNER_DOCUMENT)
            .ownerId(record != null ? record.getId() : doc.getId())
            .title(fileName)
            .fileName(fileName)
            .fileBytes(content)
            .sourceUpdatedAt(Instant.now())
            .build();
    }

    /** The record a document belongs to: its own target, else its package's. */
    private ContractRecord recordOf(final StagedDocument doc) {
        if (doc.getTargetRecord() != null) {
            return doc.getTargetRecord();
        }
        return packageDocumentRepository.findByStagedDocument_IdIn(List.of(doc.getId())).stream()
            .map(PackageDocument::getContractPackage)
            .filter(Objects::nonNull)
            .map(p -> p.getTargetRecord())
            .filter(Objects::nonNull)
            .findFirst().orElse(null);
    }

    AiIndexDTO contractRecordIndex(final Long recordId) {
        final ContractRecord record = contractRecordRepository.findById(recordId).orElse(null);
        if (record == null || record.getWorkspace() == null) {
            log.info("AI-INDEX skipped contract record {}: missing or no workspace", recordId);
            return null;
        }
        final List<String> parts = new ArrayList<>();
        parts.add("Contract record " + record.getRecordCode());
        if (record.getContractType() != null) {
            parts.add("Type: " + record.getContractType().getLabel());
        }
        if (record.getCounterparty() != null) {
            parts.add("Counterparty: " + record.getCounterparty().getName());
        }
        propertyRepository.findByContractRecord_Id(recordId).ifPresent(property -> {
            final String address = addressOf(property.getAddress());
            final String name = property.getPropertyName();
            final String text = Stream.of(name, address)
                .filter(s -> s != null && !s.isBlank()).distinct().collect(Collectors.joining(", "));
            if (!text.isBlank()) {
                parts.add("Property: " + text);
            }
        });
        if (record.getStatus() != null) {
            parts.add("Status: " + record.getStatus().getLabel());
        }
        parts.add("Workspace: " + record.getWorkspace().getName());
        return AiIndexDTO.builder()
            .appCode(AppCodeEnum.ELCM.name())
            .scopeId(record.getWorkspace().getId())
            .sourceType(ElcmSourceTypeEnum.CONTRACT_RECORD.getSourceType())
            .sourceId(record.getId())
            .ownerType(OWNER_RECORD)
            .ownerId(record.getId())
            .title(record.getRecordCode())
            .text(String.join(" | ", parts))
            .sourceUpdatedAt(instantOf(record.getUpdatedAt() != null ? record.getUpdatedAt() : record.getCreatedAt()))
            .build();
    }

    static String addressOf(final Address a) {
        if (a == null) {
            return null;
        }
        return Stream.of(a.getAddressLine1(), a.getAddressLine2(), a.getCity(), a.getState(), a.getZip())
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.joining(", "));
    }

    /** ELCM stores UTC wall-clock times (LocalDateTime.now(ZoneOffset.UTC)). */
    private static Instant instantOf(final LocalDateTime time) {
        return time == null ? Instant.now() : time.toInstant(ZoneOffset.UTC);
    }

    private static AiIndexDTOContainer container(final AiIndexDTO index, final AiIndexDeleteDTO delete) {
        final AiIndexDTOContainer c = new AiIndexDTOContainer();
        c.setAiIndexDTO(index);
        c.setAiIndexDeleteDTO(delete);
        return c;
    }

    private void post(final String path, final AiIndexDTOContainer body) throws Exception {
        final AiIndexDTOContainer answer = restServiceClient.post(
            ServiceDiscoveryEnum.ai_service.getServiceDiscoveryName(), path, body,
            new ParameterizedTypeReference<AiIndexDTOContainer>() {
            });
        log.info("AI-INDEX {}: {}", path, answer == null ? "no answer" : answer.getResultMessage());
    }

    /** Runs the work after the current transaction commits (at once when there is none). */
    private void submitAfterCommit(final String what, final Work work) {
        if (!enabled) {
            return;
        }
        final Runnable task = () -> {
            try {
                work.run();
            } catch (final Exception e) {
                log.warn("AI-INDEX failed for {}: {}", what, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.submit(task);
                }
            });
        } else {
            executor.submit(task);
        }
    }

    @FunctionalInterface
    interface Work {
        void run() throws Exception;
    }
}
