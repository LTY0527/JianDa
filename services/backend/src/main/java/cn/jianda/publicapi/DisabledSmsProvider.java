package cn.jianda.publicapi;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DisabledSmsProvider implements SmsProvider {
    @Override
    public Map<String, Object> status() {
        return Map.of(
                "enabled", false,
                "provider", "NONE",
                "message", "");
    }
}
