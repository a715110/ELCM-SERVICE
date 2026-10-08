package com.dodaso.ecosystem.elcm.service.pipeline;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One active document role, for the role dropdown in the Create Document Set dialog. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRoleOptionRow implements Serializable {
  private String code;
  private String label;
}
