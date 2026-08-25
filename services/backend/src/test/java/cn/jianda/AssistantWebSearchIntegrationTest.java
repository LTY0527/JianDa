package cn.jianda;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.jianda.ai.AiClient;
import cn.jianda.publicapi.WebSearchProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-assistant-web-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jianda.assistant.external-enabled=true"
})
@AutoConfigureMockMvc
class AssistantWebSearchIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean AiClient aiClient;
    @MockitoBean WebSearchProvider webSearchProvider;

    @BeforeEach
    void configureProviders() {
        when(webSearchProvider.status()).thenReturn(
                new WebSearchProvider.Status("tavily", "ready", "联网搜索已配置"));
        when(webSearchProvider.search(anyString(), anyInt())).thenReturn(List.of(
                new WebSearchProvider.SearchResult(
                        "上海市公园游览提示",
                        "https://www.shanghai.gov.cn/example",
                        "上海公园建议游客错峰游览，并留意当日开放信息。",
                        "www.shanghai.gov.cn")));
        when(aiClient.answerAssistant(anyString(), anyList())).thenReturn(Map.of(
                "answer", "可优先错峰游览，并在出发前查看当日开放信息。[1]",
                "used_citation_indexes", List.of(1),
                "actions", List.of("出发前打开来源页面核对。"),
                "model", "deepseek-test",
                "request_id", "web-test-1",
                "prompt_tokens", 80,
                "completion_tokens", 25,
                "total_tokens", 105,
                "elapsed_ms", 15));
    }

    @Test
    void lowRiskQuestionUsesSearchEvidenceAndReturnsClickableSource() throws Exception {
        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"周末逛上海公园有什么通用建议？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("web_ai"))
                .andExpect(jsonPath("$.data.webSearchProvider").value("tavily"))
                .andExpect(jsonPath("$.data.citations[0].kind").value("external"))
                .andExpect(jsonPath("$.data.citations[0].url")
                        .value("https://www.shanghai.gov.cn/example"))
                .andExpect(jsonPath("$.data.answer")
                        .value(org.hamcrest.Matchers.containsString("[1]")));
    }

    @Test
    void highRiskQuestionWithoutAuditedEvidenceNeverUsesWebSnippetAsAuthority() throws Exception {
        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我能领取多少养老补贴，需要什么材料？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("retrieval"))
                .andExpect(jsonPath("$.data.errorCode").value("NO_EVIDENCE"))
                .andExpect(jsonPath("$.data.answer")
                        .value(org.hamcrest.Matchers.containsString("不会猜测")));

        verify(webSearchProvider, never()).search(anyString(), anyInt());
    }
}
