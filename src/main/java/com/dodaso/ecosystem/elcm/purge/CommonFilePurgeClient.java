package com.dodaso.ecosystem.elcm.purge;

import com.dodaso.ecosystem.baseline.common.constant.ServiceDiscoveryEnum;
import com.dodaso.ecosystem.baseline.common.container.RESTReqContainer;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.common.container.FileUploadDTOContainer;
import com.dodaso.ecosystem.common.dto.FileUploadDTO;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

/**
 * ADDED 2026-10-21. Asks common-service to remove a file for good (POST /api/v1/files/purge): the
 * blob, thumbnail, converted preview and file record. The request names ELCM as the source app, so
 * common-service refuses to touch a file of another product.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommonFilePurgeClient {

  private static final String SOURCE_APP = "ELCM";

  private final RESTServiceClient restServiceClient;

  /**
   * Removes one file.
   *
   * @return true when common-service confirms the file is gone (removed now or already absent);
   *     false when the call failed, so the caller must keep its own record and retry later
   */
  public boolean purge(final Long fileUploadId) {
    try {
      final FileUploadDTOContainer request = new FileUploadDTOContainer();
      request.setFileUploadIds(Set.of(fileUploadId));
      final FileUploadDTO scope = new FileUploadDTO();
      scope.setSourceApp(SOURCE_APP);
      request.setFileUploadDTO(scope);
      final RESTReqContainer<FileUploadDTOContainer> call = new RESTReqContainer<>(
          ServiceDiscoveryEnum.common_service.getServiceDiscoveryName(),
          "/api/v1/files/purge",
          request,
          new ParameterizedTypeReference<>() {},
          HttpMethod.POST);
      final FileUploadDTOContainer response = restServiceClient.callRESTService(call);
      final boolean gone = response != null && response.getFileUploadIds() != null
          && response.getFileUploadIds().contains(fileUploadId);
      if (!gone) {
        log.warn("PURGE common-service did not confirm removal of file_upload {}", fileUploadId);
      }
      return gone;
    } catch (final Exception e) {
      log.error("PURGE could not reach common-service for file_upload {}: {}", fileUploadId,
          e.getMessage());
      return false;
    }
  }
}
