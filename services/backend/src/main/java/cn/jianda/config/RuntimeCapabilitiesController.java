package cn.jianda.config;

import cn.jianda.common.ApiResponse;
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
            int dailyTokenLimit) {
        this.llmProvider = llmProvider;
        this.externalModel = externalModel;
        this.assistantExternalEnabled = assistantExternalEnabled;
        this.crawlAutoAiEnabled = crawlAutoAiEnabled;
        this.crawlSchedulerEnabled = crawlSchedulerEnabled;
        this.dailyArticleLimit = Math.max(0, dailyArticleLimit);
        this.dailyTokenLimit = Math.max(0, dailyTokenLimit);
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
        return ApiResponse.ok(result);
    }
}
