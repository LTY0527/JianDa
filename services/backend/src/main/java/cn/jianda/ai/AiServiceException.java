package cn.jianda.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class AiServiceException extends RuntimeException {
    private final int status;
    private final Map<String, Object> detail;

    public AiServiceException(int status, Map<String, Object> detail) {
        super(safeMessage(detail));
        this.status = status;
        this.detail = Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }

    public int status() {
        return status;
    }

    public Map<String, Object> detail() {
        return detail;
    }

    public String stringValue(String key) {
        Object value = detail.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    public boolean booleanValue(String key) {
        return Boolean.TRUE.equals(detail.get(key));
    }

    private static String safeMessage(Map<String, Object> detail) {
        Object message = detail.get("message");
        return message == null ? "AI 服务暂时不可用" : String.valueOf(message);
    }
}
