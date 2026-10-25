package com.dodaso.ecosystem.elcm.integration.ecws;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADDED 2026-10-16 -- queries on the ECWS outbox. The updates are single conditional statements, so
 * two service instances never send the same row.
 */
@Repository
public interface EcwsTaskOutboxRepository extends JpaRepository<EcwsTaskOutbox, Long> {

  /** Queued rows whose time has come, oldest first. */
  @Query("select o from EcwsTaskOutbox o where o.status = 'PENDING' and o.nextAttemptAt <= :now"
      + " order by o.id")
  List<EcwsTaskOutbox> findDue(@Param("now") LocalDateTime now, Pageable page);

  /** Takes a row for sending. Returns 1 when this caller won, 0 when someone else did. */
  @Transactional
  @Modifying(clearAutomatically = true)
  @Query("update EcwsTaskOutbox o set o.status = 'SENDING', o.claimedAt = :now"
      + " where o.id = :id and o.status = 'PENDING'")
  int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

  /** Releases rows whose sender stopped half way. */
  @Transactional
  @Modifying(clearAutomatically = true)
  @Query("update EcwsTaskOutbox o set o.status = 'PENDING', o.claimedAt = null,"
      + " o.lastError = 'Released after a stalled send'"
      + " where o.status = 'SENDING' and o.claimedAt < :before")
  int recoverStale(@Param("before") LocalDateTime before);

  /*
   * closeOpen and reassignOpen run inside the ELCM action that caused them, so they must not clear
   * the persistence context (that would drop the action's own unsaved changes).
   */

  /** Marks the open CREATE rows of one package and event as no longer open. Returns how many. */
  @Transactional
  @Modifying
  @Query("update EcwsTaskOutbox o set o.taskOpen = false where o.packageId = :packageId"
      + " and o.eventCode = :eventCode and o.action = 'CREATE' and o.taskOpen = true")
  int closeOpen(@Param("packageId") Long packageId, @Param("eventCode") String eventCode);

  /** Moves the open CREATE rows of one package and event to another person. Returns how many. */
  @Transactional
  @Modifying
  @Query("update EcwsTaskOutbox o set o.assigneeLogin = :login where o.packageId = :packageId"
      + " and o.eventCode = :eventCode and o.action = 'CREATE' and o.taskOpen = true")
  int reassignOpen(@Param("packageId") Long packageId, @Param("eventCode") String eventCode,
      @Param("login") String login);

  /** How many open tasks a person holds, as far as ELCM knows. Used to pick the least loaded. */
  long countByActionAndTaskOpenTrueAndAssigneeLoginIgnoreCase(String action, String assigneeLogin);

  /** True when an earlier row of the same package is still waiting or being sent. */
  boolean existsByPackageIdAndIdLessThanAndStatusIn(Long packageId, Long id,
      Collection<String> statuses);

  // ---- ADDED 2026-10-18: reads for the Task Rail ----

  /**
   * Open CREATE rows of the given events, overdue first, then by due date, tasks without a due date
   * last (REVISED 2026-10-25: sorted in the database so paging can cut after the sort). For
   * administrators: no person filter.
   */
  @Query("select o from EcwsTaskOutbox o where o.action = 'CREATE' and o.taskOpen = true"
      + " and o.eventCode in :events order by case when o.dueDate is null then 1 else 0 end,"
      + " o.dueDate, o.id desc")
  List<EcwsTaskOutbox> findOpenForRailAll(@Param("events") Collection<String> events,
      Pageable page);

  /**
   * Open CREATE rows that could concern one person: assigned to them, not assigned to anybody, or
   * on a package they submitted or reviewed. A superset: the exact rule is TaskRailAccess.
   */
  @Query("select o from EcwsTaskOutbox o where o.action = 'CREATE' and o.taskOpen = true"
      + " and o.eventCode in :events and (lower(o.assigneeLogin) = :login"
      + " or o.assigneeLogin is null or o.packageId in (select s.contractPackage.id from"
      + " Submission s where lower(s.submittedBy) = :login or lower(s.reviewedBy) = :login))"
      + " order by case when o.dueDate is null then 1 else 0 end, o.dueDate, o.id desc")
  List<EcwsTaskOutbox> findOpenForRailPerson(@Param("events") Collection<String> events,
      @Param("login") String login, Pageable page);

  /** Closed CREATE rows closed since a time, older than a cursor id, newest first. */
  @Query("select o from EcwsTaskOutbox o where o.action = 'CREATE' and o.taskOpen = false"
      + " and o.eventCode in :events and o.updatedAt >= :since and o.id < :before"
      + " order by o.id desc")
  List<EcwsTaskOutbox> findClosedForRailAll(@Param("events") Collection<String> events,
      @Param("since") LocalDateTime since, @Param("before") Long before, Pageable page);

  /** Same as findClosedForRailAll, limited to rows that could concern one person. */
  @Query("select o from EcwsTaskOutbox o where o.action = 'CREATE' and o.taskOpen = false"
      + " and o.eventCode in :events and o.updatedAt >= :since and o.id < :before"
      + " and (lower(o.assigneeLogin) = :login or o.packageId in (select s.contractPackage.id"
      + " from Submission s where lower(s.submittedBy) = :login or lower(s.reviewedBy) = :login))"
      + " order by o.id desc")
  List<EcwsTaskOutbox> findClosedForRailPerson(@Param("events") Collection<String> events,
      @Param("since") LocalDateTime since, @Param("before") Long before,
      @Param("login") String login, Pageable page);

  /** CLOSE rows of the given packages and events: they say when and how a task was closed. */
  @Query("select o from EcwsTaskOutbox o where o.action = 'CLOSE'"
      + " and o.packageId in :packageIds and o.eventCode in :events order by o.id")
  List<EcwsTaskOutbox> findCloseRows(@Param("packageIds") Collection<Long> packageIds,
      @Param("events") Collection<String> events);

  /** Open tasks of the given events held by one person (the badge). */
  long countByActionAndTaskOpenTrueAndEventCodeInAndAssigneeLoginIgnoreCase(String action,
      Collection<String> events, String assigneeLogin);
}
