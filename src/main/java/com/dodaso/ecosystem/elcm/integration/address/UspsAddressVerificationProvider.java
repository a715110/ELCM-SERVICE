package com.dodaso.ecosystem.elcm.integration.address;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * ADDED 2026-10-25 -- the demo address check: the free USPS Addresses API (US addresses only,
 * about 60 checks per hour unless USPS raises the limit). The service gets a token with the
 * client credentials (kept until shortly before it expires) and calls the address endpoint with
 * the street, secondary line, city, state and ZIP.
 *
 * <p>Verify the endpoint paths and field names against the USPS developer portal when the keys are
 * created; they are kept in the constants below so a change touches one place.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UspsAddressVerificationProvider implements AddressVerificationProvider {

  static final String NAME = "usps";
  private static final String TOKEN_PATH = "/oauth2/v3/token";
  private static final String ADDRESS_PATH = "/addresses/v3/address";

  private final AddressVerificationProperties props;

  private String token;
  private Instant tokenExpiresAt = Instant.EPOCH;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isConfigured() {
    return notBlank(props.getUspsClientId()) && notBlank(props.getUspsClientSecret());
  }

  @Override
  public AddressVerifyResult verify(final AddressVerifyRequest request) {
    try {
      return call(request, false);
    } catch (final LimitReached e) {
      return AddressVerifyResult.of(AddressVerifyResult.UNAVAILABLE,
          "The address check limit was reached. Try again in a little while, or continue without"
              + " checking.", "USPS");
    } catch (final Exception e) {
      log.warn("USPS address check failed: {}", e.toString());
      return AddressVerifyResult.of(AddressVerifyResult.UNAVAILABLE,
          "The address check is not available right now. You can continue without checking.",
          "USPS");
    }
  }

  private AddressVerifyResult call(final AddressVerifyRequest request, final boolean retried) {
    final URI uri = UriComponentsBuilder.fromUriString(props.getUspsBaseUrl() + ADDRESS_PATH)
        .queryParam("streetAddress", clean(request.getLine1()))
        .queryParamIfPresent("secondaryAddress", java.util.Optional.ofNullable(
            blankToNull(request.getLine2())))
        .queryParam("city", clean(request.getCity()))
        .queryParam("state", clean(request.getState()))
        .queryParamIfPresent("ZIPCode", java.util.Optional.ofNullable(
            digits(request.getZip(), 5)))
        .build().encode().toUri();
    final int[] status = new int[1];
    final JsonNode body = client().get().uri(uri)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
        .accept(MediaType.APPLICATION_JSON)
        .exchange((req, res) -> {
          status[0] = res.getStatusCode().value();
          if (res.getStatusCode().is2xxSuccessful()) {
            return res.bodyTo(JsonNode.class);
          }
          return null;
        });
    final HttpStatusCode code = HttpStatusCode.valueOf(status[0]);
    if (code.value() == 401 && !retried) {
      synchronized (this) {
        token = null;
      }
      return call(request, true);
    }
    if (code.value() == 429) {
      throw new LimitReached();
    }
    if (code.value() == 404 || code.value() == 400) {
      return AddressVerifyResult.of(AddressVerifyResult.NOT_FOUND,
          "This address was not found. Check the street, city, state and ZIP, or continue if you"
              + " are sure it is right.", "USPS");
    }
    if (!code.is2xxSuccessful() || body == null || !body.has("address")) {
      throw new IllegalStateException("USPS answered HTTP " + code.value());
    }
    return toResult(request, body.get("address"));
  }

  private AddressVerifyResult toResult(final AddressVerifyRequest typed, final JsonNode found) {
    final AddressVerifyResult result = new AddressVerifyResult();
    result.setProvider("USPS");
    result.setLine1(text(found, "streetAddress"));
    result.setLine2(text(found, "secondaryAddress"));
    result.setCity(text(found, "city"));
    result.setState(text(found, "state"));
    result.setZip(text(found, "ZIPCode"));
    result.setZipPlus4(text(found, "ZIPPlus4"));
    final boolean same = AddressCompare.same(typed.getLine1(), result.getLine1())
        && AddressCompare.same(typed.getLine2(), result.getLine2())
        && AddressCompare.same(typed.getCity(), result.getCity())
        && AddressCompare.same(typed.getState(), result.getState())
        && (blankToNull(typed.getZip()) == null
        || AddressCompare.same(digits(typed.getZip(), 5), result.getZip()));
    result.setStatus(same ? AddressVerifyResult.VERIFIED : AddressVerifyResult.SUGGESTED);
    result.setMessage(same ? "The address was found." : "Did you mean this address?");
    return result;
  }

  // ---- token ----

  private synchronized String accessToken() {
    if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
      return token;
    }
    final Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "client_credentials");
    form.put("client_id", props.getUspsClientId());
    form.put("client_secret", props.getUspsClientSecret());
    final JsonNode answer = client().post().uri(props.getUspsBaseUrl() + TOKEN_PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .body(form)
        .retrieve()
        .body(JsonNode.class);
    if (answer == null || !answer.hasNonNull("access_token")) {
      throw new IllegalStateException("USPS gave no access token");
    }
    final long seconds = answer.hasNonNull("expires_in") ? answer.get("expires_in").asLong(1800)
        : 1800L;
    token = answer.get("access_token").asText();
    // Renew a minute early so a token is never used right at its end.
    tokenExpiresAt = Instant.now().plusSeconds(Math.max(60L, seconds - 60L));
    return token;
  }

  private RestClient client() {
    final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    final Duration timeout = Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds()));
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    return RestClient.builder().requestFactory(factory).build();
  }

  // ---- small helpers ----

  private static String text(final JsonNode node, final String field) {
    return node.hasNonNull(field) && !node.get(field).asText().isBlank()
        ? node.get(field).asText().trim() : null;
  }

  private static String clean(final String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** The first n digits of a ZIP such as "10001-1234", or null when there are not n digits. */
  private static String digits(final String value, final int n) {
    if (value == null) {
      return null;
    }
    final String only = value.replaceAll("\\D", "");
    return only.length() >= n ? only.substring(0, n) : null;
  }

  private static boolean notBlank(final String value) {
    return value != null && !value.isBlank();
  }

  /** The provider's 429: too many checks in the hour. */
  private static final class LimitReached extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
