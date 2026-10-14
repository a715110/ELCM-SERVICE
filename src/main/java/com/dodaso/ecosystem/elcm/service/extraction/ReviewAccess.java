package com.dodaso.ecosystem.elcm.service.extraction;

/**
 * What the signed-in user may do in the review of one package. permitted: has the review permission
 * (Reviewer role). canReview: permitted and not the person who submitted the package, so allowed to
 * edit, confirm and complete. reason: why canReview is false, else null. approvePermitted: has the
 * approve permission (Approver role). canApprove: approvePermitted and neither the submitter nor
 * the reviewer who completed the review, so allowed to approve and apply or send back.
 * approveReason: why canApprove is false, else null.
 */
public record ReviewAccess(boolean permitted, boolean canReview, String reason,
                           boolean approvePermitted, boolean canApprove, String approveReason) {

}