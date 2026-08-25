package cn.jianda.config;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.jianda.ai.AiClient;
import cn.jianda.commercial.PaymentProvider;
import cn.jianda.publicapi.WebSearchProvider;
import cn.jianda.security.JwtService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RuntimeCapabilitiesController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "LLM_PROVIDER=external",
        "EXTERNAL_LLM_MODEL=deepseek-test",
        "jianda.assistant.external-enabled=true",
        "jianda.crawl.auto-ai-enabled=false",
        "jianda.crawl.scheduler-enabled=false",
        "jianda.crawl.daily-ai-max-articles=10",
        "jianda.crawl.daily-ai-max-tokens=50000",
        "AMAP_JS_API_KEY=public-test-key",
        "AMAP_SECURITY_CODE=configured-test-code"
})
class RuntimeCapabilitiesControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AiClient aiClient;
    @MockitoBean WebSearchProvider webSearchProvider;
    @MockitoBean PaymentProvider paymentProvider;
    @MockitoBean JwtService jwtService;

    @Test
    void reportsConfiguredCapabilitiesWithoutExposingSecrets() throws Exception {
        when(webSearchProvider.status()).thenReturn(
                new WebSearchProvider.Status("tavily", "ready", "联网搜索已配置"));
        when(paymentProvider.capabilities()).thenReturn(Map.of(
                "status", "available", "provider", "local_test"));
        when(aiClient.runtimeCapabilities()).thenReturn(Map.of(
                "service", Map.of("status", "ready"),
                "llm", Map.of("status", "ready", "provider", "external"),
                "ocr", Map.of("status", "ready"),
                "webCollector", Map.of("status", "ready")));

        mvc.perform(get("/api/runtime-capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amap.status").value("ready"))
                .andExpect(jsonPath("$.data.webSearch.status").value("ready"))
                .andExpect(jsonPath("$.data.payment.status").value("available"))
                .andExpect(jsonPath("$.data.aiService.ocr.status").value("ready"))
                .andExpect(jsonPath("$.data.dailyArticleLimit").value(10))
                .andExpect(jsonPath("$.data.dailyTokenLimit").value(50000))
                .andExpect(jsonPath("$..apiKey").doesNotExist())
                .andExpect(jsonPath("$..securityCode").doesNotExist());
    }

    @Test
    void safelyReportsAiServiceAsUnreachable() throws Exception {
        when(webSearchProvider.status()).thenReturn(
                new WebSearchProvider.Status("disabled", "disabled", "联网搜索未启用"));
        when(paymentProvider.capabilities()).thenReturn(Map.of("status", "disabled"));
        when(aiClient.runtimeCapabilities()).thenThrow(
                new IllegalStateException("connection failed"));

        mvc.perform(get("/api/runtime-capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiService.service.status")
                        .value("unreachable"))
                .andExpect(jsonPath("$.data.aiService.llm.status")
                        .value("unreachable"))
                .andExpect(jsonPath("$.data.aiService.ocr.status")
                        .value("unreachable"));
    }
}
