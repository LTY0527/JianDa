package cn.jianda.ai;

import java.nio.file.Path;
import java.util.Map;

public interface AiClient {
    Map<String, Object> extractText(Path file, String fileName, String contentType);

    Map<String, Object> analyze(String title, String text, String documentType);
}
