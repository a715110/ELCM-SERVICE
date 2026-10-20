package com.dodaso.ecosystem.elcm.service.extraction;

import java.util.List;

/**
 * ADDED 2026-10-18 -- what the Extraction Quality page shows about the agent: whether it is paused
 * (and by whom, when, why), how many packages wait or are being read, and the latest control
 * events. Times are UTC, as ISO text.
 */
public record ControlState(
    boolean paused,
    String pausedBy,
    String pausedAt,
    String reason,
    long queued,
    long processing,
    List<ControlEvent> recent) {

  /** One line of the control log. packageId is null for Pause and Resume. */
  public record ControlEvent(String action, Long packageId, String actor, String reason, String at) {}
}
