package com.dodaso.ecosystem.elcm.service.extraction;

import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import com.dodaso.ecosystem.elcm.repository.pipeline.SubmissionRepository;
import com.dodaso.ecosystem.elcm.security.CallerIdentityResolver;
import com.dodaso.ecosystem.elcm.security.CallerRoleService;
import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.ElcmRolePermissions;
import com.dodaso.ecosystem.elcm.security.VerifiedCaller;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets the screen know up front whether the caller may review a package (ADDED 2026-10-12), so edit
 * controls can be hidden instead of failing with 403. It mirrors the checks the service makes: the
 * pipeline:review permission (resolved like PermissionInterceptor does: the verified token's roles,
 * else the IAMS directory; when dodaso.authz.enforce is false the permission counts as granted, as
 * the interceptor then allows everything) and the two-person rule (ReviewerSeparation). Advisory
 * only: the write endpoints still enforce both.
 */
@Service
@RequiredArgsConstructor
public class ReviewAccessService {

  private static final String SYSTEM_USER = "system";
  static final String NO_PERMISSION = "Reviewing needs the Reviewer role.";
  static final String NO_APPROVE_PERMISSION = "Approving needs the Approver role.";

  private final SubmissionRepository submissionRepository;
  private final CallerIdentityResolver callerIdentityResolver;
  private final CallerRoleService callerRoleService;
  private final ReviewerSeparation reviewerSeparation;

  @Value("${dodaso.authz.enforce:false}")
  private boolean enforce;

  @Transactional(readOnly = true)
  public ReviewAccess access(final Long packageId, final HttpServletRequest request) {
    final String caller = callerIdentityResolver.loginIdOf(request);
    final List<String> roles = rolesOf(request, caller);
    final boolean permitted =
        !enforce || ElcmRolePermissions.isGranted(roles, ElcmPermission.PIPELINE_REVIEW);
    final boolean approvePermitted =
        !enforce || ElcmRolePermissions.isGranted(roles, ElcmPermission.PIPELINE_APPROVE);
    final Submission newest = submissionRepository.findByContractPackage_Id(packageId).stream()
        .max(Comparator.comparing(Submission::getId)).orElse(null);
    final boolean reviewBlocked = reviewerSeparation.blocks(newest, caller);
    final boolean approveBlocked = reviewerSeparation.blocksApprover(newest, caller);
    return new ReviewAccess(permitted, permitted && !reviewBlocked,
        !permitted ? NO_PERMISSION : (reviewBlocked ? ReviewerSeparation.MESSAGE : null),
        approvePermitted, approvePermitted && !approveBlocked,
        !approvePermitted ? NO_APPROVE_PERMISSION
            : (approveBlocked ? ReviewerSeparation.APPROVER_MESSAGE : null));
  }

  private List<String> rolesOf(final HttpServletRequest request, final String caller) {
    final Object verified = request.getAttribute(VerifiedCaller.ATTRIBUTE);
    if (verified instanceof VerifiedCaller vc) {
      return vc.roles();
    }
    return SYSTEM_USER.equalsIgnoreCase(caller) ? List.of() : callerRoleService.rolesOf(caller);
  }
}