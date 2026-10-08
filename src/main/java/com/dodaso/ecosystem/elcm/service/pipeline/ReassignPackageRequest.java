package com.dodaso.ecosystem.elcm.service.pipeline;

import java.io.Serializable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Body of the reassign package call: the IAMS login id of the new assignee. */
@Getter
@Setter
@NoArgsConstructor
public class ReassignPackageRequest implements Serializable {
  private String assigneeId;
}
