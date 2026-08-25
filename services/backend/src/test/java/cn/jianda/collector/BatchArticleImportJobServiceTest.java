package cn.jianda.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.jianda.security.AuthUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BatchArticleImportJobServiceTest {
    private CrawlTaskService tasks;
    private SourceRegistryService registries;
    private WebArticleService articles;
    private BatchArticleImportJobService service;
    private AuthUser user;

    @BeforeEach
    void setUp() {
        tasks = mock(CrawlTaskService.class);
        registries = mock(SourceRegistryService.class);
        articles = mock(WebArticleService.class);
        Executor direct = Runnable::run;
        service = new BatchArticleImportJobService(tasks, registries, articles, new ObjectMapper(), direct);
        user = new AuthUser(1, 1, "platform_admin", "平台管理员", "PLATFORM_ADMIN", "简达平台");
        when(registries.get(7)).thenReturn(Map.of("section_url", "https://official.example/list"));
        when(tasks.createBatch(eq(7L), anyString(), eq("MANUAL"), eq("BATCH_IMPORT"), eq(user), eq(null)))
                .thenReturn(41L);
    }

    @Test
    void returnsJobImmediatelyAndPersistsSuccessfulTerminalState() {
        when(articles.preview("https://official.example/a"))
                .thenReturn(Map.of("source_registry_id", 7L));
        when(articles.importArticle("https://official.example/a", user))
                .thenReturn(Map.of("documentId", 88L));

        Map<String, Object> result = service.start(7, List.of("https://official.example/a"), user);

        assertEquals(41L, result.get("jobId"));
        assertEquals("PENDING", result.get("status"));
        verify(tasks).finish(eq(41L), anyString(),
                eq(new CrawlTaskService.Counts(1, 1, 0, 0, 0)), eq(List.of()));
    }

    @Test
    void unexpectedRunnerFailureCannotLeaveOwnedJobRunning() {
        doThrow(new IllegalStateException("fixture progress failure")).when(tasks)
                .updateImportProgress(eq(41L), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyString());

        service.start(7, List.of("https://official.example/a"), user);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CrawlTaskService.Failure>> failures = ArgumentCaptor.forClass(List.class);
        verify(tasks).finish(eq(41L), anyString(),
                eq(new CrawlTaskService.Counts(1, 0, 0, 0, 1)), failures.capture());
        assertEquals("BATCH_JOB_FAILED", failures.getValue().get(0).code());
    }
}
