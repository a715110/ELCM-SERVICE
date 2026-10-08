package com.dodaso.ecosystem.elcm.service.pipeline;

import java.io.Serializable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of the staged document delete call. The reason is optional and free text, at most
 * {@code StageDocumentService.MAX_DELETE_REASON_LENGTH} characters. It travels in a body, not a
 * query string, because it can hold any characters. Plain class, not a record, like the other
 * row and request shapes here.
 */
@Getter
@Setter
@NoArgsConstructor
public class DeleteStagedDocumentRequest implements Serializable {
  private String reason;
}
