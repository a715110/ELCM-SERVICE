package com.dodaso.ecosystem.elcm.service.pipeline;

import java.io.Serializable;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Body of the create package call: the documents to group, and an optional assignee login id. */
@Getter
@Setter
@NoArgsConstructor
public class CreateContractPackageRequest implements Serializable {
  private List<DocumentRoleSelection> documents;
  private String assigneeId;
}
