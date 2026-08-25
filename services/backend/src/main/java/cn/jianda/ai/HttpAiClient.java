package cn.jianda.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpAiClient implements AiClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI analyzeUri;
    private final URI rewriteUri;
    private final URI extractUri;
    private final URI pdfFirstPageUri;
    private final URI imageCacheUri;
    private final URI metadataUri;
    private final URI webPreviewUri;
    private final URI articleDiscoveryUri;
    private final URI assistantAnswerUri;
    private final URI assistantGeneralAnswerUri;
    private final URI assistantStatusUri;
    private final URI runtimeCapabilitiesUri;

    public HttpAiClient(ObjectMapper objectMapper, @Value("${jianda.ai-service-url}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(new DirectProxySelector())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.analyzeUri = URI.create(baseUrl + "/internal/analyze");
        this.rewriteUri = URI.create(baseUrl + "/internal/rewrite");
        this.extractUri = URI.create(baseUrl + "/internal/extract-text");
        this.pdfFirstPageUri = URI.create(baseUrl + "/internal/pdf-first-page");
        this.imageCacheUri = URI.create(baseUrl + "/internal/image-cache");
        this.metadataUri = URI.create(baseUrl + "/internal/metadata-preview?no_llm=true");
        this.webPreviewUri = URI.create(baseUrl + "/internal/web-ingest/preview");
        this.articleDiscoveryUri = URI.create(baseUrl + "/internal/article-discovery");
        this.assistantAnswerUri = URI.create(baseUrl + "/internal/assistant/answer");
        this.assistantGeneralAnswerUri = URI.create(baseUrl + "/internal/assistant/general-answer");
        this.assistantStatusUri = URI.create(baseUrl + "/internal/assistant/status");
        this.runtimeCapabilitiesUri = URI.create(baseUrl + "/internal/runtime-capabilities");
    }

    @Override
    public Map<String, Object> extractText(Path file, String fileName, String contentType) {
        return sendFile(extractUri, file, fileName, contentType, "AI extraction");
    }

    @Override
    public byte[] renderPdfFirstPage(Path file, String fileName) {
        return sendFileBytes(pdfFirstPageUri, file, fileName, "application/pdf", "PDF cover rendering");
    }

    @Override
    public ImageAsset fetchImage(String url) {
        BinaryResponse response = sendJsonBytes(
                imageCacheUri, Map.of("url", url, "allow_image_candidates", true),
                "image caching", 60_000);
        return new ImageAsset(
                response.bytes(), response.contentType(),
                response.width(), response.height());
    }

    @Override
    public Map<String, Object> previewMetadata(Path file, String fileName, String contentType) {
        return sendFile(metadataUri, file, fileName, contentType, "AI metadata preview");
    }

    @Override
    public Map<String, Object> previewWebArticle(String url, boolean allowImageCandidates, boolean robotsSoftAllow) {
        return sendJson(webPreviewUri, Map.of(
                "url", url,
                "allow_image_candidates", allowImageCandidates,
                "robots_soft_allow", robotsSoftAllow
        ), "web article preview", 90_000);
    }

    @Override
    public Map<String, Object> discoverArticles(long sourceId, String sourceUrl, String entryUrl,
                                                String method, int rateLimitSeconds) {
        return sendJson(articleDiscoveryUri, Map.of(
                "source_id", sourceId,
                "source_url", sourceUrl,
                "entry_url", entryUrl,
                "method", method,
                "rate_limit_seconds", rateLimitSeconds
        ), "article discovery", 30_000);
    }

    private Map<String, Object> sendFile(URI uri, Path file, String fileName,
                                         String contentType, String operation) {
        String boundary = "----JianDa" + UUID.randomUUID().toString().replace("-", "");
        try {
            byte[] payload = multipartPayload(boundary, file, fileName, contentType).toByteArray();
            return readResponse(send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build()), operation);
        } catch (IOException exception) {
            throw new IllegalStateException(operation + " service connection failed", exception);
        }
    }

    private byte[] sendFileBytes(URI uri, Path file, String fileName,
                                 String contentType, String operation) {
        String boundary = "----JianDa" + UUID.randomUUID().toString().replace("-", "");
        try {
            byte[] payload = multipartPayload(boundary, file, fileName, contentType).toByteArray();
            HttpResponse<byte[]> response = send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException(operation + " service returned HTTP " + status);
            }
            String mime = response.headers().firstValue("Content-Type").orElse("");
            if (mime == null || !mime.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
                throw new IllegalStateException(operation + " returned a non-image response");
            }
            byte[] bytes = response.body();
            if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024) {
                throw new IllegalStateException(operation + " returned an invalid image size");
            }
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException(operation + " service connection failed", exception);
        }
    }

    private static ByteArrayOutputStream multipartPayload(
            String boundary, Path file, String fileName, String contentType) throws IOException {
        String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
        String safeName = "upload" + extension;
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        payload.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        payload.write(("Content-Type: " + (contentType == null ? "application/octet-stream" : contentType)
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        Files.copy(file, payload);
        payload.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return payload;
    }

    @Override
    public Map<String, Object> analyze(String title, String text, String documentType,
                                       String sourceName, List<Map<String, Object>> segments,
                                       Map<String, Object> context) {
        Map<String, Object> request = analyzeRequest(title, text, documentType, sourceName, segments, context);
        return sendJson(analyzeUri, request, "AI analysis", 210_000);
    }

    @Override
    public Map<String, Object> rewrite(String title, String text, String documentType,
                                       String sourceName, List<Map<String, Object>> segments,
                                       Map<String, Object> context, Map<String, Object> factCheckpoint) {
        Map<String, Object> request = analyzeRequest(title, text, documentType, sourceName, segments, context);
        request.put("fact_checkpoint", factCheckpoint);
        return sendJson(rewriteUri, request, "AI rewrite", 210_000);
    }

    @Override
    public Map<String, Object> answerAssistant(
            String question, List<Map<String, Object>> evidence) {
        return sendJson(
                assistantAnswerUri,
                Map.of("question", question, "evidence", evidence),
                "assistant RAG",
                75_000);
    }

    @Override
    public Map<String, Object> answerGeneralAssistant(String question) {
        return sendJson(
                assistantGeneralAnswerUri,
                Map.of("question", question),
                "assistant general answer",
                75_000);
    }

    @Override
    public Map<String, Object> assistantStatus() {
        return sendGet(assistantStatusUri, "assistant status", 5_000);
    }

    @Override
    public Map<String, Object> runtimeCapabilities() {
        return sendGet(runtimeCapabilitiesUri, "AI runtime capabilities", 5_000);
    }

    private Map<String, Object> sendGet(URI uri, String operation, int readTimeout) {
        return readResponse(send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(readTimeout)).GET().build()), operation);
    }

    private Map<String, Object> analyzeRequest(String title, String text, String documentType,
                                                String sourceName, List<Map<String, Object>> segments,
                                                Map<String, Object> context) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", title);
        request.put("text", text);
        request.put("document_type", documentType);
        request.put("source_name", sourceName == null ? "" : sourceName);
        request.put("segments", segments);
        request.put("content_sha256", context.getOrDefault("content_sha256", ""));
        request.put("document_id", context.get("document_id"));
        request.put("processing_job_id", context.get("processing_job_id"));
        request.put("trace_id", context.getOrDefault("trace_id", ""));
        request.put("content_kind", context.get("content_kind"));
        request.put("prompt_version", context.get("prompt_version"));
        return request;
    }

    private Map<String, Object> sendJson(URI uri, Map<String, Object> request,
                                         String operation, int readTimeout) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(request);
            return readResponse(send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(readTimeout))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build()), operation);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(operation + " JSON processing failed", exception);
        }
    }

    private BinaryResponse sendJsonBytes(
            URI uri, Map<String, Object> request, String operation, int readTimeout) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(request);
            HttpResponse<byte[]> response = send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(readTimeout))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException(operation + " service returned HTTP " + status);
            }
            String mime = response.headers().firstValue("Content-Type").orElse("");
            if (mime == null || !mime.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
                throw new IllegalStateException(operation + " returned a non-image response");
            }
            byte[] bytes = response.body();
            if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024) {
                throw new IllegalStateException(operation + " returned an invalid image size");
            }
            return new BinaryResponse(bytes, mime.split(";", 2)[0],
                    integerHeader(response, "X-Image-Width"),
                    integerHeader(response, "X-Image-Height"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(operation + " JSON processing failed", exception);
        }
    }

    private static Integer integerHeader(HttpResponse<?> response, String name) {
        try {
            String value = response.headers().firstValue(name).orElse(null);
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record BinaryResponse(byte[] bytes, String contentType, Integer width, Integer height) {}

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI service request interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("AI service connection failed", exception);
        }
    }

    private Map<String, Object> readResponse(HttpResponse<byte[]> response, String operation) {
        int status = response.statusCode();
        byte[] fullBody = response.body() == null ? new byte[0] : response.body();
        if (status < 200 || status >= 300) {
            byte[] responseBytes = fullBody.length <= 65_536
                    ? fullBody : java.util.Arrays.copyOf(fullBody, 65_536);
            String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
            Map<String, Object> detail = new LinkedHashMap<>();
            try {
                Map<String, Object> error = objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {});
                Object rawDetail = error.get("detail");
                if (rawDetail instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> detail.put(String.valueOf(key), value));
                } else if (rawDetail instanceof String message && !message.isBlank()) {
                    detail.put("error_code", status == 503 ? "OCR_UNAVAILABLE" : "TEXT_EXTRACTION_FAILED");
                    detail.put("message", message);
                    detail.put("retryable", status >= 500);
                }
            } catch (JsonProcessingException ignored) {
                // Upstream bodies are not exposed when they are not structured safe errors.
            }
            if (detail.isEmpty()) {
                detail.put("error_code", "AI_SERVICE_UNAVAILABLE");
                detail.put("message", "AI 服务暂时不可用");
                detail.put("retryable", status >= 500);
            }
            throw new AiServiceException(status, detail);
        }
        if (fullBody.length == 0 || fullBody.length > 5 * 1024 * 1024) {
            throw new IllegalStateException(operation + " returned an invalid response size");
        }
        try {
            return objectMapper.readValue(fullBody, new TypeReference<Map<String, Object>>() {});
        } catch (IOException exception) {
            throw new IllegalStateException(operation + " returned invalid JSON", exception);
        }
    }

    private static final class DirectProxySelector extends ProxySelector {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            // Direct localhost connection has no proxy state to update.
        }
    }
}
