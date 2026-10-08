package com.dodaso.ecosystem.elcm.service.pipeline;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One document inside a package, as listed when a package row is expanded. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PackageDocumentRow implements Serializable {
  private Long stagedDocumentId;
  private String fileName;
  private String roleCode;
  private String roleLabel;
  /** Record code the document is linked to; "Unassigned" when none. */
  private String targetRecord;
}
