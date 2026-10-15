package com.dodaso.ecosystem.elcm.service.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Calls the extractor service (Docling + a local model, see the extraction pilot): one multipart
 * POST /extract with template_id and the file. The call is slow on purpose (minutes on a CPU), so
 * it only ever runs on the worker thread, never in a user request.
 *
 * Plain java.net.http on purpose: no extra dependency, and the extractor is not a Eureka service
 * (it is a container that lives next to the platform, addressed by elcm.extraction.base-url).
 */
@Component
public class ExtractorClient {

    private final ExtractionProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        // HTTP/1.1 only. The default tries an HTTP/2 upgrade on plain http, and uvicorn with
        // httptools (the extractor's server) then drops the request body, which shows up as
        // a 422 "template_id and file are required".
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    public ExtractorClient(final ExtractionProperties properties, final ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ExtractorResult extract(final String templateId, final String fileName, final String contentType,
                                   final byte[] content) throws ExtractorException {
        return extract(templateId, null, fileName, contentType, content);
    }

    /**
     * templateJson (optional) is the full template definition; the extractor then uses it and
     * templateId is only a label. Without it the extractor reads its own file named templateId.
     */
    public ExtractorResult extract(final String templateId, final String templateJson, final String fileName,
                                   final String contentType, final byte[] content) throws ExtractorException {
        final String boundary = "----elcm" + UUID.randomUUID().toString().replace("-", "");
        final byte[] body;
        try {
            body = multipart(boundary, templateId, templateJson, fileName, contentType, content);
        } catch (final IOException e) {
            throw new ExtractorException("Could not build the extractor request", e);
        }
        final HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(trimSlash(properties.getBaseUrl()) + "/extract"))
            .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        final HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractorException("Interrupted while waiting for the extractor", e);
        } catch (final IOException e) {
            throw new ExtractorException("The extractor could not be reached or timed out: " + e.getMessage(), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new ExtractorException("The extractor answered " + response.statusCode() + ": "
                + abbreviate(response.body(), 300));
        }
        try {
            final ExtractorResult result = objectMapper.readValue(response.body(), ExtractorResult.class);
            if (result.fields() == null) {
                throw new ExtractorException("The extractor answer has no fields");
            }
            return result;
        } catch (final IOException e) {
            throw new ExtractorException("The extractor answer could not be read: " + e.getMessage(), e);
        }
    }

    private static byte[] multipart(final String boundary, final String templateId, final String templateJson,
                                    final String fileName, final String contentType, final byte[] content)
        throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 1024);
        out.write(("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"template_id\"\r\n\r\n"
            + templateId + "\r\n").getBytes(StandardCharsets.UTF_8));
        if (templateJson != null && !templateJson.isBlank()) {
            out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"template_json\"\r\n\r\n"
                + templateJson + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName(fileName) + "\"\r\n"
            + "Content-Type: " + (contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** Quotes and line breaks cannot appear in a header value. */
    static String safeName(final String name) {
        final String n = name == null || name.isBlank() ? "document" : name;
        return n.replaceAll("[\"\\r\\n\\\\]", "_");
    }

    private static String trimSlash(final String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(final String text, final int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
