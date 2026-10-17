package com.dodaso.ecosystem.elcm.integration.ecws;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * ADDED 2026-10-16 -- maps elcm.ecws_task_outbox: one queued request to ECWS (create, close or
 * reassign a task). Written in the same transaction as the ELCM action, sent later by
 * EcwsTaskDispatcher. created_at and updated_at belong to the database.
 */
@Entity
@Table(name = "ecws_task_outbox")
@Getter
@Setter
public class EcwsTaskOutbox {

  public static final String CREATE = "CREATE";
  public static final String CLOSE = "CLOSE";
  public static final String REASSIGN = "REASSIGN";

  public static final String PENDING = "PENDING";
  public static final String SENDING = "SENDING";
  public static final String SENT = "SENT";
  public static final String FAILED = "FAILED";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "action", nullable = false, length = 10)
  private String action;

  @Column(name = "event_code", nullable = false, length = 10)
  private String eventCode;

  @Column(name = "package_id", nullable = false)
  private Long packageId;

  @Column(name = "package_code", length = 60)
  private String packageCode;

  @Column(name = "assignee_login", length = 100)
  private String assigneeLogin;

  @Column(name = "requestor_login", length = 100)
  private String requestorLogin;

  @Column(name = "summary", length = 200)
  private String summary;

  @Column(name = "description", length = 500)
  private String description;

  @Column(name = "comment_text", length = 500)
  private String commentText;

  @Column(name = "priority", length = 10)
  private String priority;

  /** ADDED 2026-10-16: due date for the new task; null means none. CREATE rows only. */
  @Column(name = "due_date")
  private LocalDate dueDate;

  /** ADDED 2026-10-16: the package's documents as text lines with links; CREATE rows only. */
  @Column(name = "documents_text", length = 4000)
  private String documentsText;

  @Column(name = "task_open", nullable = false)
  private boolean taskOpen;

  @Column(name = "status", nullable = false, length = 10)
  private String status = PENDING;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @Column(name = "next_attempt_at", nullable = false)
  private LocalDateTime nextAttemptAt;

  @Column(name = "claimed_at")
  private LocalDateTime claimedAt;

  @Column(name = "last_error", length = 1000)
  private String lastError;

  @Column(name = "ecws_task_id")
  private Integer ecwsTaskId;

  @Column(name = "sent_at")
  private LocalDateTime sentAt;

  @Column(name = "created_by", length = 255)
  private String createdBy;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
  private LocalDateTime updatedAt;
}
