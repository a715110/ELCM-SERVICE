package com.dodaso.ecosystem.elcm.service.pipeline;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dodaso.ecosystem.elcm.dto.AddressDTO;
import com.dodaso.ecosystem.elcm.dto.ContractRecordDTO;
import com.dodaso.ecosystem.elcm.dto.CounterpartyDTO;
import com.dodaso.ecosystem.elcm.dto.LkpContractRecordStatusDTO;
import com.dodaso.ecosystem.elcm.dto.LkpContractTypeDTO;
import com.dodaso.ecosystem.elcm.dto.PropertyDTO;
import com.dodaso.ecosystem.elcm.dto.StagedDocumentDTO;
import com.dodaso.ecosystem.elcm.dto.WorkspaceDTO;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpContractRecordStatus;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpContractType;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractRecord;
import com.dodaso.ecosystem.elcm.entity.pipeline.Counterparty;
import com.dodaso.ecosystem.elcm.entity.pipeline.Workspace;
import com.dodaso.ecosystem.elcm.entity.property.Address;
import com.dodaso.ecosystem.elcm.entity.property.Property;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpContractRecordStatusRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractRecordRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.CounterpartyRepository;
import com.dodaso.ecosystem.elcm.repository.property.AddressRepository;
import com.dodaso.ecosystem.elcm.repository.property.PropertyRepository;

import lombok.RequiredArgsConstructor;

/**
 * ADDED 2026-10-01 -- turns an "Add to Pipeline" submission's New Record /
 * Existing Record choice into a real ContractRecord, synchronously, in the
 * same transaction as StageDocumentService.createStagedDocuments() (per
 * explicit decision in chat: this is no longer a later "Preparer promotes
 * this" workflow -- the real record exists the moment the user clicks "Add
 * to Pipeline").
 *
 * createNewRecord() does four creates in sequence (Counterparty
 * find-or-create, then Address, then Property, then ContractRecord itself)
 * -- all four share this method's own @Transactional, so if any step fails
 * (e.g. the default-status lookup below), nothing from this method
 * persists, matching createStagedDocuments()'s own
 * all-or-nothing-per-submission guarantee.
 *
 * KNOWN PLACEHOLDER, same spirit as this codebase's other documented
 * placeholders (see StagedDocument's OWNER/SOURCE/COMPANY VALUES ARE
 * PLACEHOLDERS note): DEFAULT_CONTRACT_RECORD_STATUS_CODE is a guess
 * ("ACTIVE") at what a newly created record's starting
 * lkp_contract_record_status.code should be -- confirm the real value with
 * whoever owns that lookup table's seed data before relying on this in
 * anything beyond local testing.
 */
@Service
@RequiredArgsConstructor
public class RecordProvisioningService {

    /** See class Javadoc's KNOWN PLACEHOLDER note. */
    private static final String DEFAULT_CONTRACT_RECORD_STATUS_CODE = "ACTIVE";

    /** elcm.address.country has no field on the dialog yet (see
     * uploadfilesdialog.xhtml's New Record sub-panel) -- every record
     * created through this flow is domestic for now. Revisit once/if a
     * real country selector exists. */
    private static final String DEFAULT_COUNTRY = "USA";

    private final CounterpartyRepository counterpartyRepository;
    private final AddressRepository addressRepository;
    private final PropertyRepository propertyRepository;
    private final ContractRecordRepository contractRecordRepository;
    private final LkpContractRecordStatusRepository lkpContractRecordStatusRepository;

    /**
     * New Record path. dto must carry newRecordCounterparty (required --
     * UploadFilesBean already enforces this client+server side before
     * submission) and newRecordPropertyAddress/City (required for a usable
     * Address row); newRecordAddressLine2/State/Zip/Name are all optional.
     *
     * Counterparty is found-by-name-first (case-insensitive) rather than
     * always inserted -- see CounterpartyRepository.findByNameIgnoreCase()'s
     * Javadoc -- so typing "Acme Corp" for a second lease doesn't create a
     * second, disconnected Counterparty row for the same real-world party.
     * Address/Property are always newly created: unlike a counterparty, the
     * same physical address string isn't a reliable enough match key to
     * safely de-duplicate on (two different suites at "123 Main St" would
     * collide), so no find-first attempt is made for those.
     */
    @Transactional
    public ContractRecord createNewRecord(final StagedDocumentDTO dto, final Workspace workspace,
            final LkpContractType contractType) {
        final Counterparty counterparty = counterpartyRepository.findByNameIgnoreCase(dto.getNewRecordCounterparty())
            .orElseGet(() -> {
                final Counterparty created = new Counterparty();
                created.setName(dto.getNewRecordCounterparty());
                return counterpartyRepository.save(created);
            });

        final Address address = new Address();
        address.setAddressLine1(dto.getNewRecordPropertyAddress());
        address.setAddressLine2(dto.getNewRecordAddressLine2());
        address.setCity(dto.getNewRecordCity());
        address.setState(dto.getNewRecordState());
        address.setZip(dto.getNewRecordZip());
        address.setCountry(DEFAULT_COUNTRY);
        final Address savedAddress = addressRepository.save(address);

        final Property property = new Property();
        property.setAddress(savedAddress);
        // newRecordName is optional on the dialog ("Auto-generated if
        // blank" placeholder text) -- fall back to the address line so
        // property_name is never left genuinely empty.
        property.setPropertyName(dto.getNewRecordName() != null && !dto.getNewRecordName().isBlank()
            ? dto.getNewRecordName() : dto.getNewRecordPropertyAddress());
        // property_type (e.g. "COMMERCIAL"/"RESIDENTIAL") has no field on
        // the dialog at all -- left null rather than misusing
        // contractType's code (PROPERTY_LEASE/EQUIPMENT_LEASE is a
        // different concept, the lease's own contract type, not the
        // property's). Add a real selector + wire it here once that
        // distinction is actually needed.

        final LkpContractRecordStatus status = lkpContractRecordStatusRepository
            .findByCode(DEFAULT_CONTRACT_RECORD_STATUS_CODE)
            .orElseThrow(() -> new IllegalStateException(
                "Missing default lkp_contract_record_status row for code: " + DEFAULT_CONTRACT_RECORD_STATUS_CODE));

        final ContractRecord record = new ContractRecord();
        record.setRecordCode(generateRecordCode(workspace));
        record.setContractType(contractType);
        record.setWorkspace(workspace);
        record.setStatus(status);
        record.setCounterparty(counterparty);
        final ContractRecord savedRecord = contractRecordRepository.save(record);

        property.setContractRecord(savedRecord);
        propertyRepository.save(property);

        return savedRecord;
    }

    /**
     * Existing Record path. existingRecordId comes from
     * UploadFilesBean.existingRecordId -- the id the user actually picked
     * from the autocomplete (see ContractRecordOptionRow), never the raw
     * search text (existingRecordQuery), which is kept only as an audit
     * trail on staged_document (see that entity's Javadoc).
     */
    public ContractRecord findExistingRecord(final Long existingRecordId) {
        return contractRecordRepository.findById(existingRecordId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Selected existing record no longer exists (id: " + existingRecordId + ")"));
    }

    /**
     * Backs ContractRecordController.search() / the dialog's autocomplete.
     * Matches on record_code only for now -- counterparty/address aren't
     * searchable yet since ContractRecord doesn't eagerly fetch
     * counterparty or Property/Address here (and Property isn't even
     * guaranteed to exist for every record). Extend this once a real
     * "search by counterparty or address too" requirement shows up.
     */
    public List<ContractRecordOptionRow> search(final String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return contractRecordRepository
            .findTop20ByRecordCodeContainingIgnoreCaseOrderByRecordCode(query.trim())
            .stream()
            .map(r -> new ContractRecordOptionRow(r.getId(), r.getRecordCode()))
            .collect(Collectors.toList());
    }

    /**
     * ADDED 2026-10-01 -- backs ContractRecordController's GET /{id}, which
     * in turn backs the Stage Documents dashboard's new file-preview eye
     * icon: the "record details" half of that split view needs more than
     * ContractRecordOptionRow's bare id/recordCode (search()'s row, used
     * only by the Existing Record autocomplete), so this builds a fully
     * populated ContractRecordDTO instead -- workspace/contractType/status/
     * counterparty (all LAZY on the entity, see ContractRecord's Javadoc)
     * plus property/address (fetched separately via
     * PropertyRepository.findByContractRecord_Id(), since Property, not
     * ContractRecord, owns that FK).
     *
     * @Transactional(readOnly = true) keeps the Hibernate session open
     * across all four LAZY association reads below, same reasoning as
     * StageDocumentService.loadStagedDocuments() -- this method is called
     * directly from ContractRecordController (no self-invocation), so
     * Spring's transactional proxy actually applies here, unlike that
     * earlier self-invocation bug.
     *
     * propertyDTO/addressDTO are left null when no Property row exists for
     * this record yet (legacy records, or anything created before Property
     * was wired up) -- the UI's record-details panel must treat that as
     * "no address on file", not an error.
     */
    @Transactional(readOnly = true)
    public ContractRecordDTO getRecordDetail(final Long id) {
        final ContractRecord record = contractRecordRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("No contract_record row for id=" + id));

        final ContractRecordDTO dto = new ContractRecordDTO();
        dto.setId(record.getId());
        dto.setRecordCode(record.getRecordCode());
        dto.setCreatedBy(record.getCreatedBy());
        dto.setCreatedAt(record.getCreatedAt());
        dto.setUpdatedBy(record.getUpdatedBy());
        dto.setUpdatedAt(record.getUpdatedAt());

        if (record.getWorkspace() != null) {
            final WorkspaceDTO workspaceDTO = new WorkspaceDTO();
            workspaceDTO.setCode(record.getWorkspace().getCode());
            workspaceDTO.setName(record.getWorkspace().getName());
            dto.setWorkspaceDTO(workspaceDTO);
        }
        if (record.getContractType() != null) {
            final LkpContractTypeDTO contractTypeDTO = new LkpContractTypeDTO();
            contractTypeDTO.setCode(record.getContractType().getCode());
            contractTypeDTO.setLabel(record.getContractType().getLabel());
            dto.setContractTypeDTO(contractTypeDTO);
        }
        if (record.getStatus() != null) {
            final LkpContractRecordStatusDTO statusDTO = new LkpContractRecordStatusDTO();
            statusDTO.setCode(record.getStatus().getCode());
            statusDTO.setLabel(record.getStatus().getLabel());
            dto.setStatusDTO(statusDTO);
        }
        if (record.getCounterparty() != null) {
            final CounterpartyDTO counterpartyDTO = new CounterpartyDTO();
            counterpartyDTO.setId(record.getCounterparty().getId());
            counterpartyDTO.setName(record.getCounterparty().getName());
            dto.setCounterpartyDTO(counterpartyDTO);
        }

        propertyRepository.findByContractRecord_Id(id).ifPresent(property -> {
            final PropertyDTO propertyDTO = new PropertyDTO();
            propertyDTO.setId(property.getId());
            propertyDTO.setPropertyName(property.getPropertyName());
            propertyDTO.setPropertyType(property.getPropertyType());
            if (property.getAddress() != null) {
                final Address address = property.getAddress();
                final AddressDTO addressDTO = new AddressDTO();
                addressDTO.setAddressLine1(address.getAddressLine1());
                addressDTO.setAddressLine2(address.getAddressLine2());
                addressDTO.setCity(address.getCity());
                addressDTO.setState(address.getState());
                addressDTO.setZip(address.getZip());
                addressDTO.setCountry(address.getCountry());
                propertyDTO.setAddressDTO(addressDTO);
            }
            dto.setPropertyDTO(propertyDTO);
        });

        return dto;
    }

    /**
     * See ContractRecordRepository.countByWorkspace_CodeAndRecordCodeStartingWith()'s
     * Javadoc for this approach's known concurrency limitation. Format:
     * "<WORKSPACE_CODE>-00001", zero-padded to 5 digits (rolls over to 6+
     * digits naturally past 99,999 -- String.format doesn't truncate).
     */
    private String generateRecordCode(final Workspace workspace) {
        final String prefix = workspace.getCode() + "-";
        final long existingCount = contractRecordRepository
            .countByWorkspace_CodeAndRecordCodeStartingWith(workspace.getCode(), prefix);
        return prefix + String.format("%05d", existingCount + 1);
    }
}
