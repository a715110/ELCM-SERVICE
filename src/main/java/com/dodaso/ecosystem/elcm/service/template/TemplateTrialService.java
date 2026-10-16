package com.dodaso.ecosystem.elcm.service.template;

import com.dodaso.ecosystem.elcm.service.extraction.ExtractorClient;
import com.dodaso.ecosystem.elcm.service.extraction.ExtractorException;
import com.dodaso.ecosystem.elcm.service.extraction.ExtractorResult;
import com.dodaso.ecosystem.elcm.service.template.TemplateModels.Field;
import com.dodaso.ecosystem.elcm.service.template.TrialModels.Trial;
import com.dodaso.ecosystem.elcm.service.template.TrialModels.TrialDefinition;
import com.dodaso.ecosystem.elcm.service.template.TrialModels.TrialField;
import com.dodaso.ecosystem.elcm.service.template.TrialModels.TrialRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Trial run of an extraction template (ADDED 2026-10-15). The administrator picks one version
 * (usually a draft) and one document; the document is read with that version's fields and the
 * answer is shown, so a change can be judged before it is published. Nothing is saved: no
 * submission, no contract record, no change to any version.
 *
 * <p>Reading takes minutes on a CPU, so the call returns at once with a trial id and the work runs
 * on one background thread (the extractor serves one document at a time anyway). The screen asks
 * for the state every few seconds. Runs are kept in memory for an hour; they are lost when the
 * service restarts and are seen only by the instance that started them (a single instance pilot).
 *
 * <p>The definition sent to the extractor is always built from the stored fields, even for an old
 * template that still has a YAML file, so the trial shows what the version says.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateTrialService {

  public static final String QUEUED = "QUEUED";
  public static final String RUNNING = "RUNNING";
  public static final String DONE = "DONE";
  public static final String FAILED = "FAILED";
  public static final String REFUSED = "REFUSED";

  private static final int MAX_WAITING = 3;
  private static final Duration KEEP = Duration.ofHours(1);

  private final TemplateAdminService templateAdminService;
  private final ExtractorClient extractorClient;

  /**
   * Largest document accepted, in bytes. It travels as base64 inside JSON, and Jackson refuses a
   * single text longer than 20 million characters, so the default stays well under that.
   */
  @Value("${elcm.template.trial-max-bytes:12582912}")
  private long maxBytes;

  private final Map<String, Run> runs = new ConcurrentHashMap<>();

  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(MAX_WAITING),
          runnable -> {
            final Thread thread = new Thread(runnable, "template-trial");
            thread.setDaemon(true);
            return thread;
          });

  /** Accepts a document for a trial run. 404 when the version does not exist. */
  public Trial start(final Long templateId, final TrialRequest request, final String caller) {
    final TrialDefinition definition = templateAdminService.trialDefinition(templateId);
    final String fileName =
        request == null || request.fileName() == null || request.fileName().isBlank()
            ? "document"
            : request.fileName().trim();
    final byte[] content;
    try {
      content =
          request == null || request.contentBase64() == null
              ? new byte[0]
              : Base64.getDecoder().decode(request.contentBase64());
    } catch (final IllegalArgumentException e) {
      return refused(definition, fileName, "The document could not be read.");
    }
    if (content.length == 0) {
      return refused(definition, fileName, "The document is empty.");
    }
    if (content.length > maxBytes) {
      return refused(
          definition,
          fileName,
          "The document is larger than " + (maxBytes / (1024 * 1024)) + " MB. Use a smaller one.");
    }
    if (definition.fields().isEmpty()) {
      return refused(definition, fileName, "This version has no fields to look for yet.");
    }
    purge();
    final Run run = new Run(UUID.randomUUID().toString(), definition, fileName);
    runs.put(run.id, run);
    final String contentType = request.contentType();
    try {
      executor.execute(() -> execute(run, contentType, content));
    } catch (final RejectedExecutionException e) {
      runs.remove(run.id);
      return refused(
          definition, fileName, "Other trial runs are waiting. Try again when they are done.");
    }
    log.info(
        "Trial run {} of {} on {} started by {}", run.id, definition.title(), fileName, caller);
    return run.snapshot();
  }

  /** The trial run as it is now. 404 when it is unknown or older than an hour. */
  public Trial status(final String trialId) {
    final Run run = trialId == null ? null : runs.get(trialId);
    if (run == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trial run not found");
    }
    return run.snapshot();
  }

  private void execute(final Run run, final String contentType, final byte[] content) {
    run.state = RUNNING;
    final long begin = System.nanoTime();
    try {
      final ExtractorResult result =
          extractorClient.extract(
              run.definition.label(),
              run.definition.json(),
              run.fileName,
              contentType,
              content);
      run.model = result.model();
      run.fields = toFields(run.definition.fields(), result);
      run.needsReview = result.needsReviewOrEmpty();
      finish(run, begin, DONE);
    } catch (final ExtractorException e) {
      log.warn("Trial run {} failed: {}", run.id, e.getMessage());
      run.message = e.getMessage();
      finish(run, begin, FAILED);
    } catch (final RuntimeException e) {
      log.error("Trial run {} failed unexpectedly", run.id, e);
      run.message = "The trial run failed unexpectedly. See the service log.";
      finish(run, begin, FAILED);
    }
  }

  /** The time is set before the state, so a reader that sees DONE also sees the seconds. */
  private static void finish(final Run run, final long beginNanos, final String state) {
    run.seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - beginNanos);
    run.state = state;
  }

  /** The template's fields in their order, then any key the extractor added. */
  static List<TrialField> toFields(final List<Field> template, final ExtractorResult result) {
    final Map<String, ExtractorResult.Field> found = new LinkedHashMap<>();
    for (final ExtractorResult.Field f : result.fieldsOrEmpty()) {
      found.put(f.key(), f);
    }
    final List<TrialField> out = new ArrayList<>();
    final Set<String> used = new HashSet<>();
    for (final Field f : template) {
      used.add(f.key());
      final ExtractorResult.Field r = found.get(f.key());
      out.add(
          r == null
              ? new TrialField(
                  f.key(),
                  f.label(),
                  f.dataType(),
                  f.required(),
                  f.critical(),
                  null,
                  "missing",
                  null,
                  null,
                  null,
                  "The extractor returned nothing for this field.")
              : new TrialField(
                  f.key(),
                  f.label(),
                  f.dataType(),
                  f.required(),
                  f.critical(),
                  text(r.value()),
                  r.status(),
                  r.quote(),
                  r.page(),
                  r.problem(),
                  r.note()));
    }
    for (final ExtractorResult.Field r : found.values()) {
      if (!used.contains(r.key())) {
        out.add(
            new TrialField(
                r.key(),
                r.key(),
                null,
                Boolean.TRUE.equals(r.required()),
                false,
                text(r.value()),
                r.status(),
                r.quote(),
                r.page(),
                r.problem(),
                r.note()));
      }
    }
    return out;
  }

  private static String text(final JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) {
      return null;
    }
    return value.isTextual() ? value.asText() : value.toString();
  }

  private static Trial refused(
      final TrialDefinition definition, final String fileName, final String message) {
    return new Trial(
        null,
        REFUSED,
        message,
        definition.templateId(),
        definition.title(),
        fileName,
        null,
        0,
        List.of(),
        List.of());
  }

  /** Forgets runs older than an hour. */
  private void purge() {
    final Instant limit = Instant.now().minus(KEEP);
    runs.values().removeIf(r -> r.startedAt.isBefore(limit));
  }

  /** One run. The worker thread writes the fields, the request threads read them. */
  private static final class Run {
    private final String id;
    private final TrialDefinition definition;
    private final String fileName;
    private final Instant startedAt = Instant.now();
    private volatile String state = QUEUED;
    private volatile String message;
    private volatile String model;
    private volatile long seconds;
    private volatile List<TrialField> fields = List.of();
    private volatile List<String> needsReview = List.of();

    private Run(final String id, final TrialDefinition definition, final String fileName) {
      this.id = id;
      this.definition = definition;
      this.fileName = fileName;
    }

    private Trial snapshot() {
      final long elapsed =
          DONE.equals(state) || FAILED.equals(state)
              ? seconds
              : Duration.between(startedAt, Instant.now()).toSeconds();
      return new Trial(
          id,
          state,
          message,
          definition.templateId(),
          definition.title(),
          fileName,
          model,
          elapsed,
          fields,
          needsReview);
    }
  }
}
