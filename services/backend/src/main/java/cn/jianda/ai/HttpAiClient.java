package cn.jianda.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpAiClient implements AiClient {
    private final ObjectMapper objectMapper;
    private final URI analyzeUri;

    public HttpAiClient(ObjectMapper objectMapper, @Value("${jianda.ai-service-url}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.analyzeUri = URI.create(baseUrl + "/internal/analyze");
    }

    @Override
    public Map<String, Object> analyze(String title, String text, String documentType) {
        HttpURLConnection connection = null;
        try {
            byte[] payload = objectMapper.writeValueAsBytes(Map.of(
                    "title", title, "text", text, "document_type", documentType));
            connection = (HttpURLConnection) analyzeUri.toURL().openConnection(Proxy.NO_PROXY);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.getOutputStream().write(payload);

            int status = connection.getResponseCode();
            byte[] responseBytes = (status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream()).readAllBytes();
            String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("AI service returned HTTP " + status + ": " + responseBody);
            }
            return objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI JSON processing failed", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("AI service connection failed", exception);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}