package cn.jianda.ai;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface AiClient {
    Map<String, Object> extractText(Path file, String fileName, String contentType);

    Map<String, Object> previewMetadata(Path file, String fileName, String contentType);

    Map<String, Object> previewWebArticle(String url, boolean allowImageDownload);

    Map<String, Object> analyze(String title, String text, String documentType,
                                String sourceName, List<Map<String, Object>> segments,
                                Map<String, Object> context);
}
