package cn.jianda.ai;

import java.util.Map;

public interface AiClient {
    Map<String, Object> analyze(String title, String text, String documentType);
}

