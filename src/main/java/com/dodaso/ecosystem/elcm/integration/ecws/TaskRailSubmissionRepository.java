package com.dodaso.ecosystem.elcm.integration.ecws;

import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * ADDED 2026-10-18 -- the one read the Task Rail needs from submissions: who submitted and who
 * reviewed each package. Kept here, next to the rail, so the data model project is not touched.
 */
public interface TaskRailSubmissionRepository extends Repository<Submission, Long> {

  /** One line per submission of the given packages. */
  @Query("select s.contractPackage.id as packageId, s.id as submissionId,"
      + " s.submittedBy as submittedBy, s.reviewedBy as reviewedBy"
      + " from Submission s where s.contractPackage.id in :packageIds")
  List<Line> findLines(@Param("packageIds") Collection<Long> packageIds);

  /** The columns read by {@link #findLines}. */
  interface Line {

    Long getPackageId();

    Long getSubmissionId();

    String getSubmittedBy();

    String getReviewedBy();
  }
}
