package com.dodaso.ecosystem.elcm.service.pipeline;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Identity of a newly created package. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ContractPackageCreatedResponse implements Serializable {
  private Long id;
  private String packageCode;
}
