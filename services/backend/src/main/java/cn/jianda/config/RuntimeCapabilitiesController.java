package cn.jianda.config;

import cn.jianda.ai.AiClient;
import cn.jianda.common.ApiResponse;
import cn.jianda.commercial.PaymentProvider;
import cn.jianda.publicapi.WebSearchProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime-capabilities")
@PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
public class RuntimeCapabilitiesController {
    private final String llmProvider;
    private final String externalModel;
    private final boolean assistantExternalEnabled;
    private final boolean crawlAutoAiEnabled;
    private final boolean crawlSchedulerEnabled;
    private final int dailyArticleLimit;
    private final int dailyTokenLimit;
    private final AiClient aiClient;
    private final WebSearchProvider webSearchProvider;
    private final PaymentProvider paymentProvider;
    private final boolean amapConfigured;

    public RuntimeCapabilitiesController(
            @Value("${LLM_PROVIDER:mock}") String llmProvider,
            @Value("${EXTERNAL_LLM_MODEL:}") String externalModel,
            @Value("${jianda.assistant.external-enabled:false}")
            boolean assistantExternalEnabled,
            @Value("${jianda.crawl.auto-ai-enabled:false}")
            boolean crawlAutoAiEnabled,
            @Value("${jianda.crawl.scheduler-enabled:false}")
            boolean crawlSchedulerEnabled,
            @Value("${jianda.crawl.daily-ai-max-articles:0}")
            int dailyArticleLimit,
            @Value("${jianda.crawl.daily-ai-max-tokens:0}")
            int dailyTokenLimit,
            @Value("${AMAP_JS_API_KEY:}") String amapKey,
            @Value("${AMAP_SECURITY_CODE:}") String amapSecurityCode,
            AiClient aiClient,
            WebSearchProvider webSearchProvider,
            PaymentProvider paymentProvider) {
        this.llmProvider = llmProvider;
        this.externalModel = externalModel;
        this.assistantExternalEnabled = assistantExternalEnabled;
        this.crawlAutoAiEnabled = crawlAutoAiEnabled;
        this.crawlSchedulerEnabled = crawlSchedulerEnabled;
        this.dailyArticleLimit = Math.max(0, dailyArticleLimit);
        this.dailyTokenLimit = Math.max(0, dailyTokenLimit);
        this.aiClient = aiClient;
        this.webSearchProvider = webSearchProvider;
        this.paymentProvider = paymentProvider;
        this.amapConfigured = amapKey != null && !amapKey.isBlank()
                && amapSecurityCode != null && !amapSecurityCode.isBlank();
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("llmProvider", llmProvider);
        result.put("externalModel", externalModel);
        result.put("assistantExternalEnabled", assistantExternalEnabled);
        result.put("crawlAutoAiEnabled", crawlAutoAiEnabled);
        result.put("crawlSchedulerEnabled", crawlSchedulerEnabled);
        result.put("dailyArticleLimit", dailyArticleLimit);
        result.put("dailyTokenLimit", dailyTokenLimit);
        result.put("amap", capability(amapConfigured ? "ready" : "degraded",
                amapConfigured ? "高德地图浏览器配置已就绪" : "高德地图 Key 或安全密钥未配置"));
        WebSearchProvider.Status search = webSearchProvider.status();
        result.put("webSearch", Map.of(
                "status", search.state(), "message", search.message(), "provider", search.provider()));
        result.put("payment", paymentProvider.capabilities());
        try {
            result.put("aiService", aiClient.runtimeCapabilities());
        } catch (RuntimeException exception) {
            result.put("aiService", Map.of(
                    "service", capability("unreachable", "AI 服务无法连接"),
                    "llm", capability("unreachable", "无法读取模型状态"),
                    "ocr", capability("unreachable", "无法读取 OCR 状态"),
                    "webCollector", capability("unreachable", "无法读取网页采集状态")));
        }
        return ApiResponse.ok(result);
    }

    private static Map<String, Object> capability(String status, String message) {
        return Map.of("status", status, "message", message);
    }
}
