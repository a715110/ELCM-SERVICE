package com.dodaso.ecosystem.elcm.integration.ecws;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADDED 2026-10-16 -- elcm.ecws.* settings for the ELCM to ECWS task integration. Everything is
 * off until enabled is set to true, so the code can be deployed before ECWS is ready.
 */
@ConfigurationProperties(prefix = "elcm.ecws")
public class EcwsIntegrationProperties {

  /** Master switch. When false nothing is queued and nothing is sent. */
  private boolean enabled = false;

  /** Base URL of the ELCM web app, used for the link written into each task. */
  private String uiBaseUrl = "https://localhost/elcm";

  /** IAMS role whose holders get REVIEW tasks. */
  private String reviewerRole = "Reviewer";

  /** IAMS role whose holders get APPROVAL tasks. */
  private String approverRole = "Approver";

  /** How often the sender looks for queued requests. */
  private long pollIntervalMs = 15_000;

  /** Most requests sent per round. */
  private int batchSize = 20;

  /** After this many failed attempts a request is marked FAILED and left for an administrator. */
  private int maxAttempts = 10;

  /** Wait before the second attempt; doubles after each failure. */
  private long baseDelaySeconds = 30;

  /** Longest wait between attempts. */
  private long maxDelaySeconds = 3600;

  /** A request claimed by a sender that then stopped is released after this long. */
  private long claimTimeoutMinutes = 10;

  /**
   * ADDED 2026-10-16 (due dates): how many business days (Monday to Friday) a new task is given,
   * counted from the day it is created. 0 or less leaves the task without a due date.
   */
  private final DueDays dueDays = new DueDays();

  public DueDays getDueDays() {
    return dueDays;
  }

  /** Business days allowed per kind of task. */
  public static class DueDays {

    private int review = 2;
    private int approval = 1;
    private int fix = 3;
    private int failed = 1;

    public int getReview() {
      return review;
    }

    public void setReview(final int review) {
      this.review = review;
    }

    public int getApproval() {
      return approval;
    }

    public void setApproval(final int approval) {
      this.approval = approval;
    }

    public int getFix() {
      return fix;
    }

    public void setFix(final int fix) {
      this.fix = fix;
    }

    public int getFailed() {
      return failed;
    }

    public void setFailed(final int failed) {
      this.failed = failed;
    }
  }

  /**
   * ADDED 2026-10-16 (document links): most package documents listed in the comments of a Review
   * or Approval task. 0 or less turns the list off.
   */
  private int documentLinksMax = 20;

  public int getDocumentLinksMax() {
    return documentLinksMax;
  }

  public void setDocumentLinksMax(final int documentLinksMax) {
    this.documentLinksMax = documentLinksMax;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  public String getUiBaseUrl() {
    return uiBaseUrl;
  }

  public void setUiBaseUrl(final String uiBaseUrl) {
    this.uiBaseUrl = uiBaseUrl;
  }

  public String getReviewerRole() {
    return reviewerRole;
  }

  public void setReviewerRole(final String reviewerRole) {
    this.reviewerRole = reviewerRole;
  }

  public String getApproverRole() {
    return approverRole;
  }

  public void setApproverRole(final String approverRole) {
    this.approverRole = approverRole;
  }

  public long getPollIntervalMs() {
    return pollIntervalMs;
  }

  public void setPollIntervalMs(final long pollIntervalMs) {
    this.pollIntervalMs = pollIntervalMs;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(final int batchSize) {
    this.batchSize = batchSize;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(final int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public long getBaseDelaySeconds() {
    return baseDelaySeconds;
  }

  public void setBaseDelaySeconds(final long baseDelaySeconds) {
    this.baseDelaySeconds = baseDelaySeconds;
  }

  public long getMaxDelaySeconds() {
    return maxDelaySeconds;
  }

  public void setMaxDelaySeconds(final long maxDelaySeconds) {
    this.maxDelaySeconds = maxDelaySeconds;
  }

  public long getClaimTimeoutMinutes() {
    return claimTimeoutMinutes;
  }

  public void setClaimTimeoutMinutes(final long claimTimeoutMinutes) {
    this.claimTimeoutMinutes = claimTimeoutMinutes;
  }
}
