package com.dodaso.ecosystem.elcm.integration.ecws;

import com.dodaso.ecosystem.ecws.dto.ExternalTaskResultDTO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADDED 2026-10-16 -- sends queued requests to ECWS. Each round it releases rows a stopped sender
 * left behind, then takes the oldest due rows one at a time. Rows of the same package go out in
 * order: a row waits while an earlier row of its package is still queued or being sent. A failed
 * send is retried with a growing delay (doubling, capped), and after maxAttempts the row is marked
 * FAILED; an administrator can set it back to PENDING once ECWS is fixed. No database transaction
 * is held while ECWS is called.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EcwsTaskDispatcher {

  private static final int MAX_ERROR = 1000;

  private final EcwsIntegrationProperties props;
  private final EcwsTaskOutboxRepository outbox;
  private final EcwsTaskClient client;

  @Scheduled(fixedDelayString = "${elcm.ecws.poll-interval-ms:15000}")
  public void run() {
    if (!props.isEnabled()) {
      return;
    }
    try {
      dispatchDue();
    } catch (final RuntimeException e) {
      log.warn("ECWS: the sender round failed; it will try again", e);
    }
  }

  /** One round. Public so a test or an admin endpoint can call it. Returns how many were sent. */
  public int dispatchDue() {
    final LocalDateTime now = now();
    outbox.recoverStale(now.minusMinutes(props.getClaimTimeoutMinutes()));
    final List<EcwsTaskOutbox> due = outbox.findDue(now, PageRequest.of(0, props.getBatchSize()));
    int sent = 0;
    for (final EcwsTaskOutbox row : due) {
      if (outbox.existsByPackageIdAndIdLessThanAndStatusIn(row.getPackageId(), row.getId(),
          List.of(EcwsTaskOutbox.PENDING, EcwsTaskOutbox.SENDING))) {
        continue;
      }
      if (outbox.claim(row.getId(), now()) != 1) {
        continue;
      }
      if (sendOne(row)) {
        sent++;
      }
    }
    return sent;
  }

  private boolean sendOne(final EcwsTaskOutbox row) {
    try {
      final ExternalTaskResultDTO result = client.send(row);
      final EcwsTaskOutbox fresh = outbox.findById(row.getId()).orElse(row);
      fresh.setStatus(EcwsTaskOutbox.SENT);
      fresh.setSentAt(now());
      fresh.setClaimedAt(null);
      fresh.setLastError(null);
      if (EcwsTaskOutbox.CREATE.equals(fresh.getAction()) && result.getTaskId() > 0) {
        fresh.setEcwsTaskId(result.getTaskId());
      }
      outbox.save(fresh);
      return true;
    } catch (final Exception e) {
      retryLater(row, e);
      return false;
    }
  }

  private void retryLater(final EcwsTaskOutbox row, final Exception error) {
    final EcwsTaskOutbox fresh = outbox.findById(row.getId()).orElse(row);
    final int attempts = fresh.getAttempts() + 1;
    fresh.setAttempts(attempts);
    fresh.setClaimedAt(null);
    fresh.setLastError(cut(error.getClass().getSimpleName() + ": " + error.getMessage()));
    if (attempts >= props.getMaxAttempts()) {
      fresh.setStatus(EcwsTaskOutbox.FAILED);
      log.error("ECWS: request {} ({} {} for package {}) failed {} times; marked FAILED",
          fresh.getId(), fresh.getAction(), fresh.getEventCode(), fresh.getPackageId(), attempts);
    } else {
      fresh.setStatus(EcwsTaskOutbox.PENDING);
      fresh.setNextAttemptAt(now().plusSeconds(delaySeconds(attempts)));
      log.warn("ECWS: request {} failed (attempt {}); will retry: {}", fresh.getId(), attempts,
          error.getMessage());
    }
    outbox.save(fresh);
  }

  /** Base delay doubled for each earlier failure, never more than the cap. */
  long delaySeconds(final int attempts) {
    long delay = Math.max(1, props.getBaseDelaySeconds());
    for (int i = 1; i < attempts && delay < props.getMaxDelaySeconds(); i++) {
      delay *= 2;
    }
    return Math.min(delay, props.getMaxDelaySeconds());
  }

  private static String cut(final String s) {
    if (s == null) {
      return null;
    }
    return s.length() <= MAX_ERROR ? s : s.substring(0, MAX_ERROR - 3) + "...";
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }
}
