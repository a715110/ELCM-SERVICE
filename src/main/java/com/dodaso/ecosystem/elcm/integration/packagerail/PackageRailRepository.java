package com.dodaso.ecosystem.elcm.integration.packagerail;

import com.dodaso.ecosystem.elcm.entity.pipeline.ContractPackage;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * ADDED 2026-10-25 -- the reads of the Package Rail (the right-edge panel of the ECWS screens).
 * Kept here, next to the rail, so the data model project is not touched.
 *
 * <p>"Mine" is a package the person is assigned to, created, submitted or reviewed. When all is
 * true the person filter is skipped. Open is every status except the closed one, Closed is that
 * status; newest change first.
 */
public interface PackageRailRepository extends Repository<ContractPackage, Long> {

  String COLUMNS = "select p.id as id, p.packageCode as packageCode, p.status.code as statusCode,"
      + " p.status.label as statusLabel, p.workspace.code as workspace,"
      + " p.assigneeId as assigneeId, p.createdBy as createdBy, p.updatedAt as updatedAt"
      + " from ContractPackage p where ";

  String MINE_RULE = "(:all = true or lower(p.assigneeId) = :login or lower(p.createdBy) = :login"
      + " or p.id in (select s.contractPackage.id from Submission s"
      + " where lower(s.submittedBy) = :login or lower(s.reviewedBy) = :login))";

  @Query(value = COLUMNS + "p.status.code <> :closedCode and " + MINE_RULE
      + " order by p.updatedAt desc, p.id desc",
      countQuery = "select count(p) from ContractPackage p where p.status.code <> :closedCode"
          + " and " + MINE_RULE)
  Page<Line> findOpen(@Param("closedCode") String closedCode, @Param("all") boolean all,
      @Param("login") String login, Pageable page);

  @Query(value = COLUMNS + "p.status.code = :closedCode and " + MINE_RULE
      + " order by p.updatedAt desc, p.id desc",
      countQuery = "select count(p) from ContractPackage p where p.status.code = :closedCode"
          + " and " + MINE_RULE)
  Page<Line> findClosed(@Param("closedCode") String closedCode, @Param("all") boolean all,
      @Param("login") String login, Pageable page);

  /** How many documents each of the given packages holds. */
  @Query("select pd.contractPackage.id as packageId, count(pd) as total from PackageDocument pd"
      + " where pd.contractPackage.id in :ids group by pd.contractPackage.id")
  List<CountLine> countDocuments(@Param("ids") Collection<Long> ids);

  /** The columns read by the two page queries. */
  interface Line {

    Long getId();

    String getPackageCode();

    String getStatusCode();

    String getStatusLabel();

    String getWorkspace();

    String getAssigneeId();

    String getCreatedBy();

    LocalDateTime getUpdatedAt();
  }

  /** The columns read by {@link #countDocuments}. */
  interface CountLine {

    Long getPackageId();

    Long getTotal();
  }
}
