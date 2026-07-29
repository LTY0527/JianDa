package cn.jianda;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.jianda.collector.CrawlTaskService;
import cn.jianda.common.BusinessException;
import cn.jianda.security.AuthUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-crawl-task-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class CrawlTaskOperationsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired CrawlTaskService service;

    private long sourceId;
    private AuthUser user;
    private String auth;

    @BeforeEach
    void prepare() throws Exception {
        jdbc.update("DELETE FROM crawl_job_error");
        jdbc.update("DELETE FROM crawl_job WHERE original_url LIKE 'https://task-fixture.example/%'");
        jdbc.update("DELETE FROM source_registry WHERE domain='task-fixture.example'");
        jdbc.update("INSERT INTO source_registry(domain,source_name,source_type,authority_level,enabled,homepage_url,"
                + "discovery_mode,rate_limit,requires_manual_review) VALUES "
                + "('task-fixture.example','任务测试来源','GOVERNMENT','A',TRUE,'https://task-fixture.example','RSS',0,TRUE)");
        sourceId = jdbc.queryForObject("SELECT id FROM source_registry WHERE domain='task-fixture.example'", Long.class);
        user = new AuthUser(1, 1, "platform_admin", "平台管理员", "PLATFORM_ADMIN", "简达平台");
        auth = "Bearer " + login("platform_admin");
    }

    @Test
    void fullSuccessPartialSuccessAndFullFailureUseUnifiedStatuses() {
        long success = running("success");
        service.finish(success, "worker-success", new CrawlTaskService.Counts(2, 2, 0, 0, 0), List.of());
        assertJob(success, "SUCCESS", 2, 0);

        long partial = running("partial");
        service.finish(partial, "worker-partial", new CrawlTaskService.Counts(3, 1, 1, 0, 1), List.of(
                new CrawlTaskService.Failure("https://task-fixture.example/partial-bad", "FETCH", "HTTP_502",
                        "网页暂时无法访问", true)));
        assertJob(partial, "PARTIAL_SUCCESS", 1, 1);

        long failed = running("failed");
        service.finish(failed, "worker-failed", new CrawlTaskService.Counts(1, 0, 0, 0, 1), List.of(
                new CrawlTaskService.Failure("https://task-fixture.example/failed", "ROBOTS", "ROBOTS_DENIED",
                        "robots.txt 不允许采集", false)));
        assertJob(failed, "FAILED", 0, 1);
    }

    @Test
    void singleAndBatchRetryRespectRetryabilityMaximumAndIdempotency() {
        long job = running("retry-parent");
        service.finish(job, "worker-retry-parent", new CrawlTaskService.Counts(3, 0, 0, 0, 3), List.of(
                new CrawlTaskService.Failure("https://task-fixture.example/retry-a", "FETCH", "TIMEOUT", "请求超时", true),
                new CrawlTaskService.Failure("https://task-fixture.example/retry-b", "PARSE", "INVALID_XML", "XML 错误", true),
                new CrawlTaskService.Failure("https://task-fixture.example/retry-c", "SECURITY", "PRIVATE_IP", "私网地址", false)));
        List<Map<String, Object>> errors = service.errors(job);
        long firstError = ((Number) errors.get(0).get("id")).longValue();
        long nonRetryable = ((Number) errors.get(2).get("id")).longValue();
        long retryJob = service.retryError(firstError, user);
        assertEquals("PENDING", service.detail(retryJob).get("status"));
        assertThrows(BusinessException.class, () -> service.retryError(firstError, user));
        assertThrows(BusinessException.class, () -> service.retryError(nonRetryable, user));
        List<Long> batch = service.retryBatch(job, user);
        assertEquals(1, batch.size());
        assertNotEquals(retryJob, batch.get(0));

        long maxJob = running("max-retry");
        service.finish(maxJob, "worker-max-retry", new CrawlTaskService.Counts(1, 0, 0, 0, 1), List.of(
                new CrawlTaskService.Failure("https://task-fixture.example/max", "FETCH", "TIMEOUT", "请求超时", true)));
        long maxError = ((Number) service.errors(maxJob).get(0).get("id")).longValue();
        jdbc.update("UPDATE crawl_job_error SET retry_count=3 WHERE id=?", maxError);
        assertThrows(BusinessException.class, () -> service.retryError(maxError, user));

        long duplicateOne = service.create(sourceId, "https://task-fixture.example/duplicate",
                "https://task-fixture.example/duplicate", "MANUAL", "RSS", "https://task-fixture.example/feed", user, null);
        long duplicateTwo = service.create(sourceId, "https://task-fixture.example/duplicate#share",
                "https://task-fixture.example/duplicate", "MANUAL", "RSS", "https://task-fixture.example/feed", user, null);
        assertEquals(duplicateOne, duplicateTwo);
    }

    @Test
    void sourceLockBlocksConcurrentJobsAndRecoversAfterCancellation() {
        long first = service.createBatch(sourceId, "https://task-fixture.example/batch-one", "MANUAL", "RSS", user, null);
        long second = service.createBatch(sourceId, "https://task-fixture.example/batch-two", "MANUAL", "RSS", user, null);
        service.start(first, "worker-first");
        BusinessException locked = assertThrows(BusinessException.class, () -> service.start(second, "worker-second"));
        assertEquals(409, locked.getCode());
        service.cancel(first);
        service.start(second, "worker-second");
        assertEquals("RUNNING", service.detail(second).get("status"));
    }

    @Test
    void apiEnforcesPlatformAdminAndReturnsRedactedErrorsWithoutStackOrHeaders() throws Exception {
        long job = running("redaction");
        service.finish(job, "worker-redaction", new CrawlTaskService.Counts(1, 0, 0, 0, 1), List.of(
                new CrawlTaskService.Failure("https://user:password@task-fixture.example/redaction#token", "FETCH",
                        "HTTP_ERROR", "Authorization: Bearer sk-secret Cookie=session-value\n at internal.Class.run(File.java:1)", true)));
        mvc.perform(get("/api/crawl-tasks/{id}", job).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.errors[0].error_summary", not(containsString("sk-secret"))))
                .andExpect(jsonPath("$.data.errors[0].error_summary", not(containsString("session-value"))))
                .andExpect(jsonPath("$.data.errors[0].error_summary", not(containsString("File.java"))))
                .andExpect(jsonPath("$.data.errors[0].failed_url", not(containsString("password"))));
        mvc.perform(get("/api/crawl-tasks").header("Authorization", "Bearer " + login("org_admin")))
                .andExpect(status().isForbidden());
    }

    private long running(String slug) {
        String url = "https://task-fixture.example/" + slug;
        long id = service.create(sourceId, url, url, "MANUAL", "RSS", "https://task-fixture.example/feed", user, null);
        service.start(id, "worker-" + slug);
        return id;
    }

    private void assertJob(long id, String status, int added, int failed) {
        Map<String, Object> job = service.detail(id);
        assertEquals(status, job.get("status"));
        assertEquals(added, ((Number) job.get("added_count")).intValue());
        assertEquals(failed, ((Number) job.get("failed_count")).intValue());
        assertTrue(job.get("finished_at") != null);
        assertFalse(((List<?>) job.get("errors")).size() > failed);
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "Jianda@123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}
