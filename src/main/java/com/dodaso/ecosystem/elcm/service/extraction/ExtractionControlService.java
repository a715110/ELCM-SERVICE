package com.dodaso.ecosystem.elcm.service.extraction;

import com.dodaso.ecosystem.elcm.activity.ActivityEvent;
import com.dodaso.ecosystem.elcm.activity.ActivityRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * ADDED 2026-10-18 -- the public face of Pause, Resume and Stop for the controllers. Pause is
 * global and saved in the database, so it survives a restart. Stop is per package.
 */
@Service
@RequiredArgsConstructor
public class ExtractionControlService {

  private final ExtractionControlStore controlStore;
  private final ExtractionStore store;
  private final ExtractionWorker worker;
  /** ADDED 2026-10-20: the package Activity Log. */
  private final ActivityRecorder activityRecorder;

  /** Paused or not, the counts and the latest events. */
  public ControlState state() {
    return controlStore.state();
  }

  /**
   * Pauses the agent: no new package is picked up, and a package being read is put back in the queue
   * after the document it is reading. 400 without a reason.
   */
  public ControlState pause(final String actor, final String reason) {
    controlStore.pause(actor, reason);
    return controlStore.state();
  }

  /** Lets the agent work again and starts it now. */
  public ControlState resume(final String actor) {
    controlStore.resume(actor);
    worker.wake();
    return controlStore.state();
  }

  /**
   * Stops the extraction of one package: 404 no submission, 400 no reason, 409 when it is not
   * queued or being read.
   */
  public void stop(final Long packageId, final String actor, final String reason) {
    store.stop(packageId, actor, reason);
    activityRecorder.record(packageId, ActivityEvent.EXTRACTION_STOPPED, actor,
        "Extraction was stopped." + (reason != null && !reason.isBlank() ? " Reason: " + reason.trim() : ""));
  }
}
