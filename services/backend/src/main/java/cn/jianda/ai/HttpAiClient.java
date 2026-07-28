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
    private final URI extractUri;
    private final URI metadataUri;

    public HttpAiClient(ObjectMapper objectMapper, @Value("${jianda.ai-service-url}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.analyzeUri = URI.create(baseUrl + "/internal/analyze");
        this.extractUri = URI.create(baseUrl + "/internal/extract-text");
        this.metadataUri = URI.create(baseUrl + "/internal/metadata-preview");
    }

    @Override
    public Map<String, Object> extractText(Path file, String fileName, String contentType) {
        return sendFile(extractUri, file, fileName, contentType, "AI extraction");
    }

    @Override
    public Map<String, Object> previewMetadata(Path file, String fileName, String contentType) {
        return sendFile(metadataUri, file, fileName, contentType, "AI metadata preview");
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

    @Override
    public Map<String, Object> analyze(String title, String text, String documentType,
                                       String sourceName, List<Map<String, Object>> segments,
                                       Map<String, Object> context) {
        HttpURLConnection connection = null;
        try {
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
            byte[] payload = objectMapper.writeValueAsBytes(request);
            connection = (HttpURLConnection) analyzeUri.toURL().openConnection(Proxy.NO_PROXY);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(60_000);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.getOutputStream().write(payload);

            return readResponse(connection, "AI analysis");
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI JSON processing failed", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("AI service connection failed", exception);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private Map<String, Object> readResponse(HttpURLConnection connection, String operation) throws IOException {
        int status = connection.getResponseCode();
        byte[] responseBytes = (status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream()).readAllBytes();
        String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(operation + " returned HTTP " + status + ": " + responseBody);
        }
        return objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {});
    }
}
