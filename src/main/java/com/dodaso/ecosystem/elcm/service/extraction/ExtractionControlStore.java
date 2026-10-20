package com.dodaso.ecosystem.elcm.service.extraction;

import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionControl;
import com.dodaso.ecosystem.elcm.entity.extraction.ExtractionControlLog;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionControlLogRepository;
import com.dodaso.ecosystem.elcm.repository.extraction.ExtractionControlRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.SubmissionRepository;
import com.dodaso.ecosystem.elcm.service.extraction.ControlState.ControlEvent;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADDED 2026-10-18 -- the database side of Pause and Stop: the one-row switch
 * (ExtractionControl, table elcm.extraction_control) and the log of every Pause, Resume and Stop
 * (ExtractionControlLog, table elcm.extraction_control_log). Both tables are made by
 * extraction_control.sql.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExtractionControlStore {

  static final int MAX_REASON_LENGTH = 500;

  private final ExtractionControlRepository controlRepository;
  private final ExtractionControlLogRepository logRepository;
  private final SubmissionRepository submissionRepository;

  /** Is the agent paused? A missing table counts as not paused (and is logged), never as an error. */
  public boolean isPaused() {
    try {
      return controlRepository
          .findById(ExtractionControl.SINGLETON_ID)
          .map(control -> Boolean.TRUE.equals(control.getPaused()))
          .orElse(false);
    } catch (final RuntimeException e) {
      log.warn("Could not read elcm.extraction_control (run extraction_control.sql): {}",
          e.getMessage());
      return false;
    }
  }

  /** The switch, the counts and the latest events, for the admin page. */
  @Transactional(readOnly = true)
  public ControlState state() {
    final ExtractionControl control =
        controlRepository.findById(ExtractionControl.SINGLETON_ID).orElse(null);
    final boolean paused = control != null && Boolean.TRUE.equals(control.getPaused());
    return new ControlState(
        paused,
        paused ? control.getPausedBy() : null,
        paused && control.getPausedAt() != null ? control.getPausedAt().toString() : null,
        paused ? control.getReason() : null,
        submissionRepository.findByStatus_CodeOrderByIdAsc(ExtractionStore.SUBMISSION_PENDING).size(),
        submissionRepository.findByStatus_CodeOrderByIdAsc(ExtractionStore.SUBMISSION_PROCESSING).size(),
        recent());
  }

  /** Pauses the agent. Already paused: nothing changes. Returns true when this call paused it. */
  @Transactional
  public boolean pause(final String actor, final String reason) {
    final String text = requireReason(reason);
    final ExtractionControl control = controlRow();
    if (Boolean.TRUE.equals(control.getPaused())) {
      return false;
    }
    final LocalDateTime now = now();
    control.setPaused(Boolean.TRUE);
    control.setPausedBy(actor);
    control.setPausedAt(now);
    control.setReason(text);
    control.setUpdatedAt(now);
    controlRepository.save(control);
    logEvent(ExtractionControlLog.ACTION_PAUSE, null, null, actor, text);
    log.info("Extraction paused by {}: {}", actor, text);
    return true;
  }

  /** Resumes the agent. Not paused: nothing changes. Returns true when this call resumed it. */
  @Transactional
  public boolean resume(final String actor) {
    final ExtractionControl control = controlRow();
    if (!Boolean.TRUE.equals(control.getPaused())) {
      return false;
    }
    control.setPaused(Boolean.FALSE);
    control.setPausedBy(null);
    control.setPausedAt(null);
    control.setReason(null);
    control.setUpdatedAt(now());
    controlRepository.save(control);
    logEvent(ExtractionControlLog.ACTION_RESUME, null, null, actor, null);
    log.info("Extraction resumed by {}", actor);
    return true;
  }

  /** Writes the Stop of one package to the log (called in the same transaction as the stop). */
  @Transactional
  public void logStop(final Long packageId, final Long submissionId, final String actor,
      final String reason) {
    logEvent(ExtractionControlLog.ACTION_STOP, packageId, submissionId, actor, reason);
  }

  /** Who stopped this submission and why, from the log; empty when the log has no such row. */
  @Transactional(readOnly = true)
  public Optional<String[]> latestStop(final Long submissionId) {
    return logRepository
        .findFirstByActionAndSubmissionIdOrderByIdDesc(ExtractionControlLog.ACTION_STOP, submissionId)
        .map(row -> new String[] {row.getActor(), row.getReason()});
  }

  /** Trims the reason; 400 when it is empty or longer than 500 characters. */
  static String requireReason(final String reason) {
    final String text = reason == null ? "" : reason.trim();
    if (text.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required");
    }
    if (text.length() > MAX_REASON_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "The reason is longer than " + MAX_REASON_LENGTH + " characters");
    }
    return text;
  }

  /** The one control row; made on first use if the script did not insert it. */
  private ExtractionControl controlRow() {
    return controlRepository
        .findById(ExtractionControl.SINGLETON_ID)
        .orElseGet(
            () -> {
              final ExtractionControl created = new ExtractionControl();
              created.setId(ExtractionControl.SINGLETON_ID);
              created.setPaused(Boolean.FALSE);
              return created;
            });
  }

  private void logEvent(final String action, final Long packageId, final Long submissionId,
      final String actor, final String reason) {
    final ExtractionControlLog row = new ExtractionControlLog();
    row.setAction(action);
    row.setPackageId(packageId);
    row.setSubmissionId(submissionId);
    row.setActor(actor);
    row.setReason(reason);
    row.setCreatedAt(now());
    logRepository.save(row);
  }

  private List<ControlEvent> recent() {
    final List<ControlEvent> events = new ArrayList<>();
    for (final ExtractionControlLog row : logRepository.findTop10ByOrderByIdDesc()) {
      events.add(new ControlEvent(row.getAction(), row.getPackageId(), row.getActor(),
          row.getReason(), row.getCreatedAt() == null ? null : row.getCreatedAt().toString()));
    }
    return events;
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }
}
