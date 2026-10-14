package com.dodaso.ecosystem.elcm.service.pipeline;

/** Body of the classify call: the role code for one document; blank sets it back to not defined. */
public record SetDocumentRoleRequest(String roleCode) {}
