package com.dodaso.ecosystem.elcm.service.extraction;

/**
 * What the signed-in user may do in the review of one package. permitted: has the review
 * permission (Reviewer role). canReview: permitted and not the person who submitted the package,
 * so allowed to edit, confirm, complete and apply. reason: why canReview is false, else null.
 */
public record ReviewAccess(boolean permitted, boolean canReview, String reason) {
}
