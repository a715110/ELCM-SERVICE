package com.dodaso.ecosystem.elcm.integration.address;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * ADDED 2026-10-25 -- the address check behind the Verify address button on the New Record panel
 * of the Upload Files dialog. It checks the input, picks the provider named in
 * elcm.address-verification.provider and returns its answer. It does not save anything and never
 * blocks the user: every outcome is a message.
 */
@Service
@RequiredArgsConstructor
public class AddressVerificationService {

  private final AddressVerificationProperties props;
  private final List<AddressVerificationProvider> providers;

  public AddressVerifyResult verify(final AddressVerifyRequest request) {
    if (request == null || isBlank(request.getLine1()) || isBlank(request.getCity())
        || isBlank(request.getState())) {
      return AddressVerifyResult.of(AddressVerifyResult.NOT_FOUND,
          "Enter Address Line 1, City and State first, then check the address.", null);
    }
    if (!props.isEnabled()) {
      return AddressVerifyResult.of(AddressVerifyResult.UNAVAILABLE,
          "The address check is switched off.", null);
    }
    final AddressVerificationProvider provider = providers.stream()
        .filter(p -> p.name().equalsIgnoreCase(props.getProvider()))
        .findFirst().orElse(null);
    if (provider == null || !provider.isConfigured()) {
      return AddressVerifyResult.of(AddressVerifyResult.UNAVAILABLE,
          "The address check is not set up yet. You can continue without checking.", null);
    }
    return provider.verify(request);
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
