package cn.jianda.ai;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpAiClient implements AiClient {
    private final RestClient client;

    public HttpAiClient(RestClient.Builder builder, @Value("${jianda.ai-service-url}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> analyze(String title, String text, String documentType) {
        return client.post().uri("/internal/analyze").body(Map.of("title", title, "text", text,
                "document_type", documentType)).retrieve().body(Map.class);
    }
}
