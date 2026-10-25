package com.dodaso.ecosystem.elcm.controller;

import com.dodaso.ecosystem.elcm.integration.address.AddressVerificationService;
import com.dodaso.ecosystem.elcm.integration.address.AddressVerifyRequest;
import com.dodaso.ecosystem.elcm.integration.address.AddressVerifyResult;
import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADDED 2026-10-25 -- checks a typed address for the Verify address button of the Upload Files
 * dialog (New Record). Same permission as uploading: pipeline:submit. Read only: nothing is saved.
 */
@RestController
@RequestMapping("/api/v1/pipeline/address")
@RequiredArgsConstructor
public class AddressVerificationController {

  private final AddressVerificationService addressVerificationService;

  @PostMapping("/verify")
  @RequiresPermission(ElcmPermission.PIPELINE_SUBMIT)
  public AddressVerifyResult verify(@RequestBody final AddressVerifyRequest request) {
    return addressVerificationService.verify(request);
  }
}
