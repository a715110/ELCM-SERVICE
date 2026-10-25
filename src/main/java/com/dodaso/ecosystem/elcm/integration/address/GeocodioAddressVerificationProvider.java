package com.dodaso.ecosystem.elcm.integration.address;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * ADDED 2026-10-25 -- the address check used for the demo while the USPS keys are not available:
 * Geocodio (free tier of about 2,500 lookups a day, US addresses). It cleans, standardizes and
 * geocodes an address. It is not USPS certified and does no delivery point check, so "found" means
 * a house-level match, not proof that the mail carrier delivers there.
 *
 * <p>The typed address is sent as one {@code q} string. A result counts as found only when its
 * accuracy type is a house-level one and it has a street number and street; a match on the street
 * or city alone is reported as not found. The paths and field names are constants below; the
 * response fields are read under both the older names (state, zip) and the newer ones
 * (state_province, postal_code). Confirm against the Geocodio documentation on the first run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeocodioAddressVerificationProvider implements AddressVerificationProvider {

  static final String NAME = "geocodio";
  private static final String GEOCODE_PATH = "/geocode";
  private static final Set<String> HOUSE_LEVEL =
      Set.of("rooftop", "point", "range_interpolation", "nearest_rooftop_match");

  private final AddressVerificationProperties props;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isConfigured() {
    return props.getGeocodioApiKey() != null && !props.getGeocodioApiKey().isBlank();
  }

  @Override
  public AddressVerifyResult verify(final AddressVerifyRequest request) {
    try {
      return call(request);
    } catch (final LimitReached e) {
      return AddressVerifyResult.of(AddressVerifyResult.UNAVAILABLE,
          "The address check limit was reached for today. Try again later, or continue without"
              + " checking.", "Geocodio");
    } catch (final Exception e) {
      log.warn("Geocodio address check failed: {}", e.toString());
      return AddressVerifyResult.of(AddressVerifyResult.UNAVAILABLE,
          "The address check is not available right now. You can continue without checking.",
          "Geocodio");
    }
  }

  private AddressVerifyResult call(final AddressVerifyRequest request) {
    final URI uri = UriComponentsBuilder.fromUriString(props.getGeocodioBaseUrl() + GEOCODE_PATH)
        .queryParam("q", oneLine(request))
        .queryParam("country", "US")
        .queryParam("limit", 1)
        .build().encode().toUri();
    final int[] status = new int[1];
    final JsonNode body = client().get().uri(uri)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getGeocodioApiKey())
        .accept(MediaType.APPLICATION_JSON)
        .exchange((req, res) -> {
          status[0] = res.getStatusCode().value();
          return res.getStatusCode().is2xxSuccessful() ? res.bodyTo(JsonNode.class) : null;
        });
    if (status[0] == 429) {
      throw new LimitReached();
    }
    if (status[0] == 422) {
      return notFound();
    }
    if (status[0] < 200 || status[0] > 299 || body == null) {
      // 401 and 403 mean a bad key or no permission: the log line shows the status.
      throw new IllegalStateException("Geocodio answered HTTP " + status[0]);
    }
    final JsonNode results = body.path("results");
    if (!results.isArray() || results.isEmpty()) {
      return notFound();
    }
    final JsonNode best = results.get(0);
    final JsonNode parts = best.path("address_components");
    final String type = best.path("accuracy_type").asText("");
    if (!HOUSE_LEVEL.contains(type) || text(parts, "number") == null
        || text(parts, "formatted_street") == null) {
      return notFound();
    }
    return toResult(request, parts);
  }

  private AddressVerifyResult toResult(final AddressVerifyRequest typed, final JsonNode parts) {
    final AddressVerifyResult result = new AddressVerifyResult();
    result.setProvider("Geocodio");
    result.setLine1(text(parts, "formatted_street") != null
        ? joinNonBlank(text(parts, "number"), text(parts, "formatted_street")) : null);
    result.setLine2(joinNonBlank(text(parts, "unit_type"), text(parts, "unit_number")));
    result.setCity(text(parts, "city"));
    result.setState(first(parts, "state_province", "state"));
    result.setZip(first(parts, "postal_code", "zip"));
    final boolean same = AddressCompare.same(typed.getLine1(), result.getLine1())
        && (blank(typed.getLine2()) && blank(result.getLine2())
        || AddressCompare.same(typed.getLine2(), result.getLine2()))
        && AddressCompare.same(typed.getCity(), result.getCity())
        && AddressCompare.same(typed.getState(), result.getState())
        && (blank(typed.getZip()) || AddressCompare.same(
        firstFive(typed.getZip()), result.getZip()));
    result.setStatus(same ? AddressVerifyResult.VERIFIED : AddressVerifyResult.SUGGESTED);
    result.setMessage(same ? "The address was found." : "Did you mean this address?");
    return result;
  }

  private static AddressVerifyResult notFound() {
    return AddressVerifyResult.of(AddressVerifyResult.NOT_FOUND,
        "This address was not found. Check the street number, street, city, state and ZIP, or"
            + " continue if you are sure it is right.", "Geocodio");
  }

  private RestClient client() {
    final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    final Duration timeout = Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds()));
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    return RestClient.builder().requestFactory(factory).build();
  }

  // ---- small helpers ----

  private static String oneLine(final AddressVerifyRequest r) {
    final StringBuilder sb = new StringBuilder(r.getLine1().trim());
    if (!blank(r.getLine2())) {
      sb.append(' ').append(r.getLine2().trim());
    }
    sb.append(", ").append(r.getCity().trim()).append(", ").append(r.getState().trim());
    if (!blank(r.getZip())) {
      sb.append(' ').append(r.getZip().trim());
    }
    return sb.toString();
  }

  private static String text(final JsonNode node, final String field) {
    return node.hasNonNull(field) && !node.get(field).asText().isBlank()
        ? node.get(field).asText().trim() : null;
  }

  private static String first(final JsonNode node, final String a, final String b) {
    final String value = text(node, a);
    return value != null ? value : text(node, b);
  }

  private static String joinNonBlank(final String a, final String b) {
    if (a == null) {
      return b;
    }
    return b == null ? a : a + " " + b;
  }

  private static String firstFive(final String zip) {
    final String digits = zip == null ? "" : zip.replaceAll("\\D", "");
    return digits.length() >= 5 ? digits.substring(0, 5) : digits;
  }

  private static boolean blank(final String value) {
    return value == null || value.isBlank();
  }

  /** The provider's 429: too many lookups. */
  private static final class LimitReached extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
