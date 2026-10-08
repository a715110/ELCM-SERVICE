package com.dodaso.ecosystem.elcm.service.pipeline;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One document going into a package, with the role chosen for it. A null or blank roleCode
 * means "not defined yet" and is stored as the UNDEFINED role; the preparer sets it later.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRoleSelection implements Serializable {
  private Long stagedDocumentId;
  private String roleCode;
}
