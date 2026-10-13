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
 * Lets the screen know up front whether the caller may review a package (ADDED 2026-10-12), so
 * edit controls can be hidden instead of failing with 403. It mirrors the checks the service makes:
 * the pipeline:review permission (resolved like PermissionInterceptor does: the verified token's
 * roles, else the IAMS directory; when dodaso.authz.enforce is false the permission counts as
 * granted, as the interceptor then allows everything) and the two-person rule (ReviewerSeparation).
 * Advisory only: the write endpoints still enforce both.
 */
@Service
@RequiredArgsConstructor
public class ReviewAccessService {

    private static final String SYSTEM_USER = "system";
    static final String NO_PERMISSION = "Reviewing needs the Reviewer role.";

    private final SubmissionRepository submissionRepository;
    private final CallerIdentityResolver callerIdentityResolver;
    private final CallerRoleService callerRoleService;
    private final ReviewerSeparation reviewerSeparation;

    @Value("${dodaso.authz.enforce:false}")
    private boolean enforce;

    @Transactional(readOnly = true)
    public ReviewAccess access(final Long packageId, final HttpServletRequest request) {
        final String caller = callerIdentityResolver.loginIdOf(request);
        final boolean permitted = !enforce || ElcmRolePermissions.isGranted(rolesOf(request, caller),
            ElcmPermission.PIPELINE_REVIEW);
        if (!permitted) {
            return new ReviewAccess(false, false, NO_PERMISSION);
        }
        final Submission newest = submissionRepository.findByContractPackage_Id(packageId).stream()
            .max(Comparator.comparing(Submission::getId)).orElse(null);
        if (reviewerSeparation.blocks(newest, caller)) {
            return new ReviewAccess(true, false, ReviewerSeparation.MESSAGE);
        }
        return new ReviewAccess(true, true, null);
    }

    private List<String> rolesOf(final HttpServletRequest request, final String caller) {
        final Object verified = request.getAttribute(VerifiedCaller.ATTRIBUTE);
        if (verified instanceof VerifiedCaller vc) {
            return vc.roles();
        }
        return SYSTEM_USER.equalsIgnoreCase(caller) ? List.of() : callerRoleService.rolesOf(caller);
    }
}
