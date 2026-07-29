package cn.jianda.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
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

    public HttpAiClient(ObjectMapper objectMapper, @Value("${jianda.ai-service-url}") String baseUrl) {
        this.objectMapper = objectMapper;
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
    public Map<String, Object> previewWebArticle(String url, boolean allowImageCandidates) {
        return sendJson(webPreviewUri, Map.of(
                "url", url,
                "allow_image_candidates", allowImageCandidates
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
        HttpURLConnection connection = null;
        String boundary = "----JianDa" + UUID.randomUUID().toString().replace("-", "");
        try {
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

            connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(60_000);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.size());
            payload.writeTo(connection.getOutputStream());
            return readResponse(connection, operation);
        } catch (IOException exception) {
            throw new IllegalStateException(operation + " service connection failed", exception);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private byte[] sendFileBytes(URI uri, Path file, String fileName,
                                 String contentType, String operation) {
        HttpURLConnection connection = null;
        String boundary = "----JianDa" + UUID.randomUUID().toString().replace("-", "");
        try {
            ByteArrayOutputStream payload = multipartPayload(
                    boundary, file, fileName, contentType);
            connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(60_000);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.size());
            payload.writeTo(connection.getOutputStream());
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException(operation + " service returned HTTP " + status);
            }
            String mime = connection.getContentType();
            if (mime == null || !mime.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
                throw new IllegalStateException(operation + " returned a non-image response");
            }
            byte[] bytes = connection.getInputStream().readNBytes(10 * 1024 * 1024 + 1);
            if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024) {
                throw new IllegalStateException(operation + " returned an invalid image size");
            }
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException(operation + " service connection failed", exception);
        } finally {
            if (connection != null) connection.disconnect();
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
        HttpURLConnection connection = null;
        try {
            byte[] payload = objectMapper.writeValueAsBytes(request);
            connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(readTimeout);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.getOutputStream().write(payload);
            return readResponse(connection, operation);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(operation + " JSON processing failed", exception);
        } catch (IOException exception) {
            throw new IllegalStateException(operation + " service connection failed", exception);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private BinaryResponse sendJsonBytes(
            URI uri, Map<String, Object> request, String operation, int readTimeout) {
        HttpURLConnection connection = null;
        try {
            byte[] payload = objectMapper.writeValueAsBytes(request);
            connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(readTimeout);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.getOutputStream().write(payload);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException(operation + " service returned HTTP " + status);
            }
            String mime = connection.getContentType();
            if (mime == null || !mime.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
                throw new IllegalStateException(operation + " returned a non-image response");
            }
            byte[] bytes = connection.getInputStream().readNBytes(10 * 1024 * 1024 + 1);
            if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024) {
                throw new IllegalStateException(operation + " returned an invalid image size");
            }
            return new BinaryResponse(bytes, mime.split(";", 2)[0],
                    integerHeader(connection, "X-Image-Width"),
                    integerHeader(connection, "X-Image-Height"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(operation + " JSON processing failed", exception);
        } catch (IOException exception) {
            throw new IllegalStateException(operation + " service connection failed", exception);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Integer integerHeader(HttpURLConnection connection, String name) {
        try {
            String value = connection.getHeaderField(name);
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record BinaryResponse(byte[] bytes, String contentType, Integer width, Integer height) {}

    private Map<String, Object> readResponse(HttpURLConnection connection, String operation) throws IOException {
        int status = connection.getResponseCode();
        var stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        byte[] responseBytes = stream == null ? new byte[0] : stream.readNBytes(65_536);
        String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
        if (status < 200 || status >= 300) {
            Map<String, Object> detail = new LinkedHashMap<>();
            try {
                Map<String, Object> error = objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {});
                Object rawDetail = error.get("detail");
                if (rawDetail instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> detail.put(String.valueOf(key), value));
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
        return objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {});
    }
}
