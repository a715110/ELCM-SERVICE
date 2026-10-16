package com.dodaso.ecosystem.elcm.service.template;

import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionTemplate;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionTemplateEvent;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionTemplateField;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpContractType;
import com.dodaso.ecosystem.elcm.entity.lookup.LkpFieldDataType;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionTemplateEventRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionTemplateFieldRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionTemplateRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpContractTypeRepository;
import com.dodaso.ecosystem.elcm.repository.lookup.LkpFieldDataTypeRepository;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Change;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.DeleteImpact;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Detail;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Diff;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Event;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Field;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Option;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Options;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Result;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Summary;
import com.dodaso.ecosystem.elcm.service.template.TrialModels.TrialDefinition;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Administration of extraction templates (ADDED 2026-10-14). A template has versions. A version is
 * a DRAFT while it is edited, becomes PUBLISHED when an administrator publishes it with a reason
 * (the one that extraction uses), and is RETIRED when a newer version is published. Published and
 * retired versions are never changed, and every extraction job keeps pointing at the version it
 * used. Every change is written to the change log.
 *
 * <p>Wrong input is answered with a list of messages in the Result (nothing is saved). A missing
 * version is 404 and a request that does not fit the state of the version is 409.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateAdminService {

  public static final String DRAFT = "DRAFT";
  public static final String PUBLISHED = "PUBLISHED";
  public static final String RETIRED = "RETIRED";

  /** ADDED 2026-10-15 - change log type of a template that was deleted for good. */
  public static final String TEMPLATE_DELETED = "TEMPLATE_DELETED";

  private final ExtractionTemplateRepository templateRepository;
  private final ExtractionTemplateFieldRepository fieldRepository;
  private final ExtractionTemplateEventRepository eventRepository;
  private final LkpFieldDataTypeRepository dataTypeRepository;
  private final LkpContractTypeRepository contractTypeRepository;
  private final EntityManager entityManager;

  /** Fields Apply depends on; a published template that has one cannot lose it. */
  @Value("${elcm.template.protected-keys:lessor_name}")
  private String protectedKeys;

  /** When true the administrator who last edited a draft cannot publish it. */
  @Value("${elcm.template.separate-publisher:false}")
  private boolean separatePublisher;

  @Transactional(readOnly = true)
  public List<Summary> list() {
    return templateRepository.findAllByOrderByNameAscIdDesc().stream().map(this::summary).toList();
  }

  @Transactional(readOnly = true)
  public Detail get(final Long id) {
    return detail(require(id));
  }

  @Transactional(readOnly = true)
  public List<Event> events(final Long id) {
    final ExtractionTemplate t = require(id);
    return eventRepository.findByTemplateNameOrderByIdDesc(t.getName()).stream()
        .map(
            e ->
                new Event(
                    e.getTemplateId(),
                    e.getVersionLabel(),
                    e.getEventType(),
                    e.getReason(),
                    e.getSummary(),
                    e.getCreatedBy(),
                    e.getCreatedAt() == null ? null : e.getCreatedAt().toString()))
        .toList();
  }

  @Transactional(readOnly = true)
  public Diff compare(final Long fromId, final Long toId) {
    final ExtractionTemplate from = require(fromId);
    final ExtractionTemplate to = require(toId);
    final List<Change> changes = TemplateRules.diff(fields(fromId), fields(toId));
    return new Diff(from.getId(), from.getVersion(), to.getId(), to.getVersion(), changes);
  }

  /**
   * ADDED 2026-10-15 - what a trial run needs: the stored fields of one version and the definition
   * built from them. Always built from the fields, even when the version still has an extractor
   * YAML file, so the trial shows what the version says.
   */
  @Transactional(readOnly = true)
  public TrialDefinition trialDefinition(final Long id) {
    final ExtractionTemplate t = require(id);
    final List<Field> fields = fields(id);
    final String label = TemplateDefinitions.label(t);
    return new TrialDefinition(
        t.getId(),
        t.getName() + ", version " + t.getVersion(),
        label,
        TemplateRules.definitionJson(label, t.getName(), fields),
        fields);
  }

  @Transactional(readOnly = true)
  public Options options() {
    final List<Option> types =
        dataTypeRepository.findAll().stream()
            .filter(t -> Boolean.TRUE.equals(t.getIsActive()))
            .sorted(Comparator.comparing(LkpFieldDataType::getSortOrder))
            .map(t -> new Option(t.getId(), t.getCode(), t.getLabel()))
            .toList();
    final List<Option> contracts =
        contractTypeRepository.findAll().stream()
            .filter(t -> Boolean.TRUE.equals(t.getIsActive()))
            .sorted(Comparator.comparing(LkpContractType::getSortOrder))
            .map(t -> new Option(t.getId(), t.getCode(), t.getLabel()))
            .toList();
    return new Options(types, contracts);
  }

  /** Starts a brand new template: an empty draft, version 1. */
  @Transactional
  public Result createTemplate(
      final String name, final Long contractTypeId, final String reason, final String caller) {
    final List<String> problems = new ArrayList<>();
    final String cleanName = name == null ? "" : name.trim().replaceAll("\\s+", " ");
    if (cleanName.length() < 3 || cleanName.length() > 150) {
      problems.add("The name must be 3 to 150 characters.");
    } else if (!templateRepository.findByNameOrderByIdDesc(cleanName).isEmpty()) {
      problems.add("A template with this name already exists.");
    }
    final Optional<LkpContractType> type =
        contractTypeId == null ? Optional.empty() : contractTypeRepository.findById(contractTypeId);
    if (type.isEmpty()) {
      problems.add("Choose the contract type the template is for.");
    }
    if (reason != null && reason.trim().length() > TemplateRules.MAX_REASON) {
      problems.add("The reason is longer than " + TemplateRules.MAX_REASON + " characters.");
    }
    if (!problems.isEmpty()) {
      return new Result(problems, null);
    }
    final ExtractionTemplate t = new ExtractionTemplate();
    t.setName(cleanName);
    t.setContractType(type.get());
    t.setVersion("1");
    t.setFieldCount(0);
    t.setIsActive(false);
    t.setExtractorTemplateId(TemplateRules.slug(cleanName) + "_v1");
    t.setStatus(DRAFT);
    t.setChangeReason(blankToNull(reason));
    final ExtractionTemplate saved = templateRepository.save(t);
    log(saved, ExtractionTemplateEvent.DRAFT_CREATED, blankToNull(reason), "New template.", caller);
    return new Result(List.of(), detail(saved));
  }

  /**
   * Copies a published or retired version into a new draft (also how an old version is restored).
   */
  @Transactional
  public Detail createDraft(final Long basedOnId, final String reason, final String caller) {
    final ExtractionTemplate base = require(basedOnId);
    if (DRAFT.equals(base.getStatus())) {
      throw conflict("That version is already a draft.");
    }
    templateRepository
        .findFirstByNameAndStatus(base.getName(), DRAFT)
        .ifPresent(
            d -> {
              throw conflict(
                  "There is already an open draft (version "
                      + d.getVersion()
                      + "). Edit it or discard it first.");
            });
    final ExtractionTemplate d = new ExtractionTemplate();
    d.setName(base.getName());
    d.setContractType(base.getContractType());
    d.setVersion(nextVersion(base.getName()));
    d.setIsActive(false);
    d.setExtractorTemplateId(base.getExtractorTemplateId());
    d.setStatus(DRAFT);
    d.setBasedOnId(base.getId());
    d.setChangeReason(blankToNull(reason));
    final List<ExtractionTemplateField> source =
        fieldRepository.findByTemplate_IdOrderByDisplayOrder(base.getId());
    d.setFieldCount(source.size());
    final ExtractionTemplate saved = templateRepository.save(d);
    for (final ExtractionTemplateField f : source) {
      final ExtractionTemplateField copy = new ExtractionTemplateField();
      copy.setTemplate(saved);
      copy.setFieldKey(f.getFieldKey());
      copy.setLabel(f.getLabel());
      copy.setDataType(f.getDataType());
      copy.setIsCritical(f.getIsCritical());
      copy.setIsRequired(f.getIsRequired());
      copy.setDisplayOrder(f.getDisplayOrder());
      copy.setDescription(f.getDescription());
      copy.setKeywords(f.getKeywords());
      copy.setAllowedValues(f.getAllowedValues());
      copy.setValueHints(f.getValueHints());
      fieldRepository.save(copy);
    }
    log(
        saved,
        ExtractionTemplateEvent.DRAFT_CREATED,
        blankToNull(reason),
        "Copied from version " + base.getVersion() + " (" + source.size() + " fields).",
        caller);
    return detail(saved);
  }

  /** Replaces the fields of a draft. */
  @Transactional
  public Result saveDraft(final Long id, final List<Field> incoming, final String caller) {
    final ExtractionTemplate t = requireDraft(id);
    final List<Field> clean = new ArrayList<>();
    int order = 1;
    for (final Field f : incoming == null ? List.<Field>of() : incoming) {
      final Field n = TemplateRules.normalize(f);
      clean.add(
          new Field(
              n.id(),
              n.key(),
              n.label(),
              n.dataType(),
              n.critical(),
              n.required(),
              order++,
              n.description(),
              n.keywords(),
              n.allowedValues(),
              n.valueHints()));
    }
    final List<String> problems = TemplateRules.problems(clean, typeCodes());
    if (!problems.isEmpty()) {
      return new Result(problems, null);
    }
    final List<Change> changes = TemplateRules.diff(fields(id), clean);
    fieldRepository.deleteByTemplate_Id(id);
    fieldRepository.flush();
    for (final Field f : clean) {
      fieldRepository.save(toEntity(t, f));
    }
    t.setFieldCount(clean.size());
    templateRepository.save(t);
    if (!changes.isEmpty()) {
      log(t, ExtractionTemplateEvent.DRAFT_SAVED, null, TemplateRules.summary(changes), caller);
    }
    return new Result(List.of(), detail(t));
  }

  /** Deletes a draft. The change log keeps a line saying it was discarded. */
  @Transactional
  public void discard(final Long id, final String reason, final String caller) {
    final ExtractionTemplate t = requireDraft(id);
    log(
        t,
        ExtractionTemplateEvent.DRAFT_DISCARDED,
        blankToNull(reason),
        "Draft discarded.",
        caller);
    fieldRepository.deleteByTemplate_Id(id);
    fieldRepository.flush();
    templateRepository.delete(t);
  }

  /** Makes a draft the version in use; the version in use until now is retired. */
  @Transactional
  public Result publish(final Long id, final String reason, final String caller) {
    final ExtractionTemplate t = requireDraft(id);
    final String why = reason == null ? "" : reason.trim();
    final List<String> problems = new ArrayList<>();
    if (why.length() < TemplateRules.MIN_REASON || why.length() > TemplateRules.MAX_REASON) {
      problems.add(
          "Give a reason of "
              + TemplateRules.MIN_REASON
              + " to "
              + TemplateRules.MAX_REASON
              + " characters.");
    }
    final List<Field> after = fields(id);
    if (after.isEmpty()) {
      problems.add("A template needs at least one field before it can be published.");
    }
    problems.addAll(TemplateRules.problems(after, typeCodes()));
    final Optional<ExtractionTemplate> current =
        templateRepository.findByNameOrderByIdDesc(t.getName()).stream()
            .filter(v -> PUBLISHED.equals(v.getStatus()))
            .findFirst();
    List<Change> changes = List.of();
    if (current.isPresent()) {
      final List<Field> before = fields(current.get().getId());
      changes = TemplateRules.diff(before, after);
      final Set<String> keys = after.stream().map(Field::key).collect(Collectors.toSet());
      for (final String key : TemplateRules.splitList(protectedKeys)) {
        if (before.stream().anyMatch(f -> key.equals(f.key())) && !keys.contains(key)) {
          problems.add(
              "The field "
                  + key
                  + " is used when values are applied to a contract record and cannot be removed.");
        }
      }
    }
    templateRepository
        .findFirstByContractType_IdAndIsActiveTrueOrderByIdDesc(t.getContractType().getId())
        .filter(other -> !other.getName().equals(t.getName()))
        .ifPresent(
            other ->
                problems.add(
                    "The contract type "
                        + t.getContractType().getLabel()
                        + " is already served by the template "
                        + other.getName()
                        + ". Only one template can be in use for a contract type."));
    if (separatePublisher && caller != null && caller.equals(lastEditor(t))) {
      problems.add(
          "A different administrator must publish this draft (it was last edited by you).");
    }
    if (!problems.isEmpty()) {
      return new Result(problems, null);
    }
    for (final ExtractionTemplate v : templateRepository.findByNameOrderByIdDesc(t.getName())) {
      if (PUBLISHED.equals(v.getStatus())) {
        v.setStatus(RETIRED);
        v.setIsActive(false);
        templateRepository.save(v);
      }
    }
    t.setStatus(PUBLISHED);
    t.setIsActive(true);
    t.setChangeReason(why);
    t.setPublishedBy(caller);
    t.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC));
    t.setFieldCount(after.size());
    templateRepository.save(t);
    log(
        t,
        ExtractionTemplateEvent.PUBLISHED,
        why,
        current.isPresent()
            ? "Replaces version "
                + current.get().getVersion()
                + ". "
                + TemplateRules.summary(changes)
            : "First published version, " + after.size() + " fields.",
        caller);
    log.info(
        "Extraction template {} version {} published by {}", t.getName(), t.getVersion(), caller);
    return new Result(List.of(), detail(t));
  }

  /** ADDED 2026-10-15 - what deleting the template this version belongs to would remove. */
  @Transactional(readOnly = true)
  public DeleteImpact deleteImpact(final Long id) {
    final ExtractionTemplate t = require(id);
    final List<ExtractionTemplate> versions = templateRepository.findByNameOrderByIdDesc(t.getName());
    return new DeleteImpact(
        t.getName(),
        t.getContractType().getLabel(),
        versions.size(),
        versions.stream().anyMatch(v -> PUBLISHED.equals(v.getStatus())),
        jobsUsing(versions));
  }

  /**
   * ADDED 2026-10-15 - deletes a template for good: every version and its fields. Refused (nothing
   * changes, the messages say why) when the name is not typed right, the reason is too short, or any
   * document was ever read with a version, because deleting would erase its results and review
   * history. The change log keeps a line saying the template was deleted.
   */
  @Transactional
  public Result deleteTemplate(
      final Long id, final String confirmName, final String reason, final String caller) {
    final ExtractionTemplate t = require(id);
    final List<ExtractionTemplate> versions = templateRepository.findByNameOrderByIdDesc(t.getName());
    final String why = reason == null ? "" : reason.trim();
    final List<String> problems = new ArrayList<>();
    if (confirmName == null || !confirmName.trim().equals(t.getName())) {
      problems.add("Type the name of the template exactly as shown to confirm.");
    }
    if (why.length() < TemplateRules.MIN_REASON || why.length() > TemplateRules.MAX_REASON) {
      problems.add(
          "Give a reason of "
              + TemplateRules.MIN_REASON
              + " to "
              + TemplateRules.MAX_REASON
              + " characters.");
    }
    final long jobs = jobsUsing(versions);
    if (jobs > 0) {
      problems.add(
          "Documents were read with this template ("
              + jobs
              + " extraction jobs). Deleting it would erase their results and review history, so it"
              + " cannot be deleted here.");
    }
    if (!problems.isEmpty()) {
      return new Result(problems, null);
    }
    final ExtractionTemplate newest = versions.get(0);
    final String published =
        versions.stream()
            .filter(v -> PUBLISHED.equals(v.getStatus()))
            .map(v -> "published version " + v.getVersion())
            .findFirst()
            .orElse("no published version");
    log(
        newest,
        TEMPLATE_DELETED,
        why,
        "Template deleted: " + versions.size() + " versions, " + published + ".",
        caller);
    for (final ExtractionTemplate v : versions) {
      fieldRepository.deleteByTemplate_Id(v.getId());
    }
    fieldRepository.flush();
    templateRepository.deleteAll(versions);
    log.info(
        "Extraction template {} deleted by {} ({} versions)", t.getName(), caller, versions.size());
    return new Result(List.of(), null);
  }

  private long jobsUsing(final List<ExtractionTemplate> versions) {
    final List<Long> ids = versions.stream().map(ExtractionTemplate::getId).toList();
    return entityManager
        .createQuery("select count(j) from ExtractionJob j where j.template.id in :ids", Long.class)
        .setParameter("ids", ids)
        .getSingleResult();
  }

  // ---- helpers ----

  private ExtractionTemplate require(final Long id) {
    return templateRepository
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found"));
  }

  private ExtractionTemplate requireDraft(final Long id) {
    final ExtractionTemplate t = require(id);
    if (!DRAFT.equals(t.getStatus())) {
      throw conflict("Only a draft can be changed. Create a draft from this version first.");
    }
    return t;
  }

  private static ResponseStatusException conflict(final String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private String nextVersion(final String name) {
    return TemplateRules.nextVersion(
        templateRepository.findByNameOrderByIdDesc(name).stream()
            .map(ExtractionTemplate::getVersion)
            .toList());
  }

  private Set<String> typeCodes() {
    return dataTypeRepository.findAll().stream()
        .filter(t -> Boolean.TRUE.equals(t.getIsActive()))
        .sorted(Comparator.comparing(LkpFieldDataType::getSortOrder))
        .map(LkpFieldDataType::getCode)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private String lastEditor(final ExtractionTemplate t) {
    return eventRepository.findByTemplateNameOrderByIdDesc(t.getName()).stream()
        .filter(e -> t.getId().equals(e.getTemplateId()))
        .filter(
            e ->
                ExtractionTemplateEvent.DRAFT_SAVED.equals(e.getEventType())
                    || ExtractionTemplateEvent.DRAFT_CREATED.equals(e.getEventType()))
        .map(ExtractionTemplateEvent::getCreatedBy)
        .findFirst()
        .orElse(null);
  }

  private List<Field> fields(final Long templateId) {
    return fieldRepository.findByTemplate_IdOrderByDisplayOrder(templateId).stream()
        .map(TemplateDefinitions::toField)
        .toList();
  }

  private Detail detail(final ExtractionTemplate t) {
    final Long openDraft =
        templateRepository
            .findFirstByNameAndStatus(t.getName(), DRAFT)
            .map(ExtractionTemplate::getId)
            .orElse(null);
    return new Detail(summary(t), fields(t.getId()), openDraft);
  }

  private Summary summary(final ExtractionTemplate t) {
    return new Summary(
        t.getId(),
        t.getName(),
        t.getContractType().getId(),
        t.getContractType().getLabel(),
        t.getVersion(),
        t.getStatus(),
        Boolean.TRUE.equals(t.getIsActive()),
        t.getFieldCount() == null ? 0 : t.getFieldCount(),
        t.getChangeReason(),
        t.getBasedOnId(),
        t.getPublishedBy(),
        t.getPublishedAt() == null ? null : t.getPublishedAt().toString(),
        t.getUpdatedBy(),
        t.getUpdatedAt() == null ? null : t.getUpdatedAt().toString());
  }

  private ExtractionTemplateField toEntity(final ExtractionTemplate t, final Field f) {
    final ExtractionTemplateField e = new ExtractionTemplateField();
    e.setTemplate(t);
    e.setFieldKey(f.key());
    e.setLabel(f.label());
    e.setDataType(
        dataTypeRepository
            .findByCode(f.dataType())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown data type")));
    e.setIsCritical(f.critical());
    e.setIsRequired(f.required());
    e.setDisplayOrder(f.order());
    e.setDescription(f.description());
    e.setKeywords(f.keywords());
    e.setAllowedValues(f.allowedValues());
    e.setValueHints(f.valueHints());
    return e;
  }

  private void log(
      final ExtractionTemplate t,
      final String type,
      final String reason,
      final String summary,
      final String caller) {
    final ExtractionTemplateEvent e = new ExtractionTemplateEvent();
    e.setTemplateName(t.getName());
    e.setVersionLabel(t.getVersion());
    e.setTemplateId(t.getId());
    e.setEventType(type);
    e.setReason(reason);
    e.setSummary(
        summary == null || summary.length() <= 2000 ? summary : summary.substring(0, 1996) + "...");
    e.setCreatedBy(caller == null || caller.isBlank() ? "system" : caller);
    e.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
    eventRepository.save(e);
  }

  private static String blankToNull(final String s) {
    if (s == null) {
      return null;
    }
    final String t = s.trim();
    if (t.isEmpty()) {
      return null;
    }
    return t.length() <= TemplateRules.MAX_REASON ? t : t.substring(0, TemplateRules.MAX_REASON);
  }
}
