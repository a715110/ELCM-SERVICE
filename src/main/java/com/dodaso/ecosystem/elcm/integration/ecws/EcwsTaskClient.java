package com.dodaso.ecosystem.elcm.integration.ecws;

import com.dodaso.ecosystem.baseline.common.constant.ServiceDiscoveryEnum;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.ecws.dto.ExternalTaskRequestDTO;
import com.dodaso.ecosystem.ecws.dto.ExternalTaskResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

/**
 * ADDED 2026-10-16 -- the one place that talks to ECWS's external task endpoints. Throws on any
 * failure so the dispatcher can retry later.
 */
@Component
@RequiredArgsConstructor
public class EcwsTaskClient {

  static final String SUBSYSTEM = "ELCM";
  private static final String BASE = "/externalTaskController/";

  private final RESTServiceClient restServiceClient;

  /** Sends one outbox row and returns ECWS's answer. */
  public ExternalTaskResultDTO send(final EcwsTaskOutbox row) throws Exception {
    final ExternalTaskRequestDTO request = new ExternalTaskRequestDTO();
    request.setSubsystemId(SUBSYSTEM);
    request.setExternalRefPk(Math.toIntExact(row.getPackageId()));
    request.setUiPageId("package");
    request.setUiSectionId(row.getEventCode());
    final String endpoint;
    switch (row.getAction()) {
      case EcwsTaskOutbox.CREATE -> {
        endpoint = "createExternalTask";
        request.setTaskTypeName(taskTypeName(row.getEventCode()));
        request.setSummary(row.getSummary());
        request.setDescription(row.getDescription());
        request.setAssignedTo(row.getAssigneeLogin());
        request.setRequestor(row.getRequestorLogin());
        request.setPriority(row.getPriority());
        request.setDocumentsText(row.getDocumentsText());
        request.setDueDate(row.getDueDate() == null ? null : row.getDueDate().toString());
      }
      case EcwsTaskOutbox.CLOSE -> {
        endpoint = "closeExternalTasks";
        request.setComment(row.getCommentText());
      }
      case EcwsTaskOutbox.REASSIGN -> {
        endpoint = "reassignExternalTasks";
        request.setAssignedTo(row.getAssigneeLogin());
        request.setComment(row.getCommentText());
      }
      default -> throw new IllegalStateException("Unknown outbox action " + row.getAction());
    }
    final ExternalTaskResultDTO result = restServiceClient.post(
        ServiceDiscoveryEnum.ecws_service.getServiceDiscoveryName(), BASE + endpoint, request,
        new ParameterizedTypeReference<ExternalTaskResultDTO>() {
        });
    if (result == null) {
      throw new IllegalStateException("ECWS returned no answer for " + endpoint);
    }
    return result;
  }

  /** The ECWS task type (subsystem ELCM) for an event; see ecws_elcm_task_types.sql. */
  static String taskTypeName(final String eventCode) {
    return switch (eventCode) {
      case EcwsTaskPublisher.REVIEW -> "ELCM Review";
      case EcwsTaskPublisher.APPROVAL -> "ELCM Approval";
      case EcwsTaskPublisher.FIX -> "ELCM Fix";
      case EcwsTaskPublisher.FAILED -> "ELCM Extraction Failed";
      default -> throw new IllegalStateException("Unknown event code " + eventCode);
    };
  }
}
