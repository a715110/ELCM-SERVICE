package com.dodaso.ecosystem.elcm.packagedetail;

import com.dodaso.ecosystem.elcm.entity.pipeline.ContractPackage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * ADDED 2026-10-25 -- the reads of the Package Details screen. Kept here, next to the feature, so
 * the data model project is not touched. Documents come from ContractPackageService and the
 * activity log from ActivityReadService, so only the header, the submissions and the access check
 * are read here.
 */
public interface PackageDetailRepository extends Repository<ContractPackage, Long> {

  /** The header of one package; the target record may be missing (left join). */
  @Query("select p.id as id, p.packageCode as packageCode, p.status.code as statusCode,"
      + " p.status.label as statusLabel, p.workspace.code as workspaceCode,"
      + " p.workspace.name as workspaceName, t.recordCode as targetRecord,"
      + " p.assigneeId as assigneeId, p.createdBy as createdBy, p.createdAt as createdAt,"
      + " p.updatedBy as updatedBy, p.updatedAt as updatedAt"
      + " from ContractPackage p left join p.targetRecord t where p.id = :id")
  Optional<Header> findHeader(@Param("id") Long id);

  /** Every submission of the package, newest first. */
  @Query("select s.id as id, s.status.code as statusCode, s.status.label as statusLabel,"
      + " s.submittedBy as submittedBy, s.submittedAt as submittedAt, s.claimedBy as claimedBy,"
      + " s.reviewedBy as reviewedBy, s.reviewedAt as reviewedAt,"
      + " s.declineReason as declineReason, s.declinedBy as declinedBy,"
      + " s.declinedAt as declinedAt, s.correctionNote as correctionNote"
      + " from Submission s where s.contractPackage.id = :id order by s.id desc")
  List<SubmissionLine> findSubmissions(@Param("id") Long id);

  /**
   * 1 when the person is involved in the package, else 0: assigned to it, created it, or submitted
   * or reviewed one of its submissions. The same rule as "Mine" in the Package Rail. login is lower
   * case.
   */
  @Query("select count(p) from ContractPackage p where p.id = :id and (lower(p.assigneeId) = :login"
      + " or lower(p.createdBy) = :login or p.id in (select s.contractPackage.id from Submission s"
      + " where lower(s.submittedBy) = :login or lower(s.reviewedBy) = :login))")
  long countInvolved(@Param("id") Long id, @Param("login") String login);

  /** The columns of {@link #findHeader}. */
  interface Header {

    Long getId();

    String getPackageCode();

    String getStatusCode();

    String getStatusLabel();

    String getWorkspaceCode();

    String getWorkspaceName();

    String getTargetRecord();

    String getAssigneeId();

    String getCreatedBy();

    LocalDateTime getCreatedAt();

    String getUpdatedBy();

    LocalDateTime getUpdatedAt();
  }

  /** The columns of {@link #findSubmissions}. */
  interface SubmissionLine {

    Long getId();

    String getStatusCode();

    String getStatusLabel();

    String getSubmittedBy();

    LocalDateTime getSubmittedAt();

    String getClaimedBy();

    String getReviewedBy();

    LocalDateTime getReviewedAt();

    String getDeclineReason();

    String getDeclinedBy();

    LocalDateTime getDeclinedAt();

    String getCorrectionNote();
  }
}
