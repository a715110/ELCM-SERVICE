package com.dodaso.ecosystem.elcm.service.pipeline;

import java.io.Serializable;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Body of the add documents to an existing package call. */
@Getter
@Setter
@NoArgsConstructor
public class AddPackageDocumentsRequest implements Serializable {
  private List<DocumentRoleSelection> documents;
}
