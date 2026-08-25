package cn.jianda.ai;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface AiClient {
    Map<String, Object> extractText(Path file, String fileName, String contentType);

    byte[] renderPdfFirstPage(Path file, String fileName);

    ImageAsset fetchImage(String url);

    Map<String, Object> previewMetadata(Path file, String fileName, String contentType);

    Map<String, Object> previewWebArticle(String url, boolean allowImageCandidates);

    Map<String, Object> discoverArticles(long sourceId, String sourceUrl, String entryUrl,
                                         String method, int rateLimitSeconds);

    Map<String, Object> analyze(String title, String text, String documentType,
                                String sourceName, List<Map<String, Object>> segments,
                                Map<String, Object> context);

    Map<String, Object> rewrite(String title, String text, String documentType,
                                String sourceName, List<Map<String, Object>> segments,
                                Map<String, Object> context, Map<String, Object> factCheckpoint);

    Map<String, Object> answerAssistant(String question, List<Map<String, Object>> evidence);

    Map<String, Object> answerGeneralAssistant(String question);

    Map<String, Object> assistantStatus();

    Map<String, Object> runtimeCapabilities();

    record ImageAsset(byte[] bytes, String contentType, Integer width, Integer height) {}
}
