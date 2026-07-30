package cn.jianda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.jianda.ai.AiClient;
import cn.jianda.ai.AiQueueService;
import cn.jianda.security.AuthUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-ai-budget;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jianda.upload-dir=./target/test-ai-budget-uploads",
        "jianda.crawl.auto-ai-enabled=false",
        "jianda.crawl.daily-ai-max-articles=2",
        "jianda.crawl.daily-ai-max-tokens=70",
        "jianda.crawl.ai-max-input-characters=80",
        "jianda.crawl.ai-max-articles-per-task=1"
})
class AiQueueBudgetIntegrationTest {
    @Autowired AiQueueService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AiClient aiClient;
    private long sequence;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM ai_execution_audit");
        jdbc.update("DELETE FROM ai_budget_reservation");
        jdbc.update("DELETE FROM ai_budget_usage");
        jdbc.update("DELETE FROM ai_processing_queue");
        service.setClock(Clock.systemUTC());
        sequence++;
    }

    @Test
    void defaultEnqueueDoesNotCallAiAndRequiresApproval() {
        long document = insertDocument("默认关闭 " + sequence, "正文 " + sequence);
        Map<String, Object> queued = service.enqueue(document, null, null);
        assertEquals("WAITING_APPROVAL", queued.get("status"));
        assertEquals("AUTO_AI_DISABLED", queued.get("reason_code"));
        verify(aiClient, never()).analyze(anyString(), anyString(), anyString(), anyString(), anyList(), anyMap());
    }

    @Test
    void allowsThenSettlesAndBlocksArticleAndTokenBudgets() {
        long first = insertDocument("允许 " + sequence, "一二三四五六七八九十");
        AiQueueService.Reservation allowed = reserveAutomatically(first, null);
        assertTrue(allowed.allowed());
        service.settle(allowed, 40, true, "stub", "mock-model");

        long second = insertDocument("token " + sequence, "甲".repeat(80));
        AiQueueService.Reservation tokenBlocked = reserveAutomatically(second, null);
        assertFalse(tokenBlocked.allowed());
        assertEquals("BUDGET_TOKENS", tokenBlocked.reasonCode());
        assertFalse(jdbc.queryForObject("SELECT executed FROM ai_execution_audit ORDER BY id DESC LIMIT 1", Boolean.class));
        assertEquals(0, jdbc.queryForObject("SELECT actual_tokens FROM ai_execution_audit ORDER BY id DESC LIMIT 1", Integer.class));
        assertNotNull(tokenBlocked.estimatedRecoveryAt());

        jdbc.update("UPDATE ai_budget_usage SET actual_tokens=0,settled_articles=2,reserved_articles=0,reserved_tokens=0");
        long third = insertDocument("article " + sequence, "短正文");
        AiQueueService.Reservation articleBlocked = reserveAutomatically(third, null);
        assertFalse(articleBlocked.allowed());
        assertEquals("BUDGET_ARTICLES", articleBlocked.reasonCode());
    }

    @Test
    void sourceBudgetOverlongDuplicateAndDateSwitchAreSafe() {
        long source = source();
        jdbc.update("UPDATE source_registry SET daily_article_budget=1,daily_token_budget=20 WHERE id=?", source);
        long document = insertWebDocument("来源 " + sequence, "来源正文", source);
        AiQueueService.Reservation allowed = reserveAutomatically(document, source);
        assertTrue(allowed.allowed());
        service.settle(allowed, 10, true, "stub", "mock-model");

        long exhausted = insertWebDocument("来源耗尽 " + sequence, "第二正文", source);
        assertEquals("BUDGET_ARTICLES", reserveAutomatically(exhausted, source).reasonCode());

        long overlong = insertDocument("过长 " + sequence, "长".repeat(81));
        assertEquals("INPUT_TOO_LONG", service.reserveForManual(overlong, null, admin()).reasonCode());

        Map<String, Object> duplicate = service.enqueue(document, source, null);
        Map<String, Object> same = service.enqueue(document, source, null);
        assertEquals(duplicate.get("id"), same.get("id"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_processing_queue WHERE content_hash=?",
                Integer.class, duplicate.get("content_hash")));

        service.setClock(Clock.fixed(Instant.parse("2026-07-30T16:01:00Z"), ZoneOffset.UTC));
        long nextDay = insertDocument("日期切换 " + sequence, "新日正文");
        assertTrue(service.reserveForManual(nextDay, null, admin()).allowed());
    }

    @Test
    void concurrentReservationCannotOverspend() throws Exception {
        long seed = insertDocument("并发已用 " + sequence, "已用正文");
        AiQueueService.Reservation used = reserveAutomatically(seed, null);
        service.settle(used, 1, true, "stub", "mock-model");
        long left = insertDocument("并发左 " + sequence, "左侧正文");
        long right = insertDocument("并发右 " + sequence, "右侧正文");
        long leftQueue = approvedQueue(left, null);
        long rightQueue = approvedQueue(right, null);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        Thread one = new Thread(() -> reserveQueue(start, leftQueue, allowed));
        Thread two = new Thread(() -> reserveQueue(start, rightQueue, allowed));
        one.start();
        two.start();
        start.countDown();
        one.join();
        two.join();
        assertEquals(1, allowed.get());
    }

    @Test
    void manualReservationIgnoresAutomaticBudgetsAndDoesNotChargeUsage() {
        jdbc.update("INSERT INTO ai_budget_usage(budget_date,scope_type,scope_id,settled_articles,actual_tokens) "
                + "VALUES (CURRENT_DATE,'GLOBAL',0,2,70)");
        long document = insertDocument("手工预算豁免 " + sequence, "手工上传材料正文");
        AiQueueService.Reservation reservation = service.reserveForManual(document, null, admin());
        assertTrue(reservation.allowed());
        assertTrue(jdbc.queryForObject("SELECT budget_exempt FROM ai_budget_reservation WHERE id=?",
                Boolean.class, reservation.reservationId()));
        service.settle(reservation, 12, true, "external", "deepseek-test");
        Map<String, Object> usage = jdbc.queryForMap(
                "SELECT settled_articles,actual_tokens FROM ai_budget_usage WHERE scope_type='GLOBAL' AND scope_id=0");
        assertEquals(2, ((Number) usage.get("settled_articles")).intValue());
        assertEquals(70L, ((Number) usage.get("actual_tokens")).longValue());
    }

    @Test
    void settledAndReservedContentHashesCannotBeReservedAgain() {
        long first = insertDocument("重复正文一 " + sequence, "完全相同正文");
        long second = insertDocument("重复正文二 " + sequence, "完全相同正文");
        AiQueueService.Reservation reserved = service.reserveForManual(first, null, admin());
        assertTrue(reserved.allowed());
        AiQueueService.Reservation whileReserved = service.reserveForManual(second, null, admin());
        assertFalse(whileReserved.allowed());
        assertEquals("CONTENT_HASH_DUPLICATE", whileReserved.reasonCode());
        service.markExecutionStarted(reserved);
        service.settle(reserved, 3, true, "stub", "mock-model");
        assertEquals("CONTENT_HASH_DUPLICATE", service.reserveForManual(second, null, admin()).reasonCode());
        assertTrue(jdbc.queryForObject("SELECT executed FROM ai_execution_audit WHERE result='PROCESSING' "
                + "ORDER BY id DESC LIMIT 1", Boolean.class));
    }

    @Test
    void taskCapUsesSharedBatchRootAndSettlementIsIdempotent() {
        long source = source();
        long rootJob = insertCrawlJob(source, null, "https://example.invalid/root-" + sequence);
        long childJob = insertCrawlJob(source, rootJob, "https://example.invalid/child-" + sequence);
        long firstDocument = insertDocument("根任务一 " + sequence, "根任务正文一");
        long secondDocument = insertDocument("根任务二 " + sequence, "根任务正文二");
        long firstQueue = ((Number) service.enqueue(firstDocument, source, rootJob).get("id")).longValue();
        long secondQueue = ((Number) service.enqueue(secondDocument, source, childJob).get("id")).longValue();
        service.approve(firstQueue, admin());
        service.approve(secondQueue, admin());
        AiQueueService.Reservation first = service.reserveQueue(firstQueue);
        assertTrue(first.allowed());
        AiQueueService.Reservation blocked = service.reserveQueue(secondQueue);
        assertFalse(blocked.allowed());
        assertEquals("TASK_ARTICLE_LIMIT", blocked.reasonCode());

        service.markExecutionStarted(first);
        service.settle(first, 4, true, "stub", "mock-model");
        service.settle(first, 4, true, "stub", "mock-model");
        service.release(first, "DUPLICATE_RELEASE");
        assertEquals(1, jdbc.queryForObject("SELECT settled_articles FROM ai_budget_usage "
                + "WHERE scope_type='GLOBAL'", Integer.class));
        assertEquals(4L, jdbc.queryForObject("SELECT actual_tokens FROM ai_budget_usage "
                + "WHERE scope_type='GLOBAL'", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_execution_audit WHERE queue_id=? AND result='SUCCEEDED'",
                Integer.class, firstQueue));
    }

    @Test
    void waitingBudgetCanBeManuallyExecutedAfterRecoveryDate() {
        long document = insertDocument("恢复预算 " + sequence, "恢复后的正文");
        long queueId = ((Number) service.enqueue(document, null, null).get("id")).longValue();
        service.approve(queueId, admin());
        jdbc.update("UPDATE ai_processing_queue SET status='WAITING_BUDGET',available_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.parse("2026-07-30T16:00:00Z")), queueId);
        service.setClock(Clock.fixed(Instant.parse("2026-07-30T15:59:00Z"), ZoneOffset.UTC));
        assertFalse(service.reserveQueue(queueId).allowed());
        service.setClock(Clock.fixed(Instant.parse("2026-07-30T16:01:00Z"), ZoneOffset.UTC));
        assertTrue(service.reserveQueue(queueId).allowed());
    }

    @Test
    void manualApprovalAndFailurePreserveQueueForRetry() {
        long document = insertDocument("批准失败 " + sequence, "可恢复正文");
        long queueId = ((Number) service.enqueue(document, null, null).get("id")).longValue();
        service.approve(queueId, admin());
        AiQueueService.Reservation reservation = service.reserveQueue(queueId);
        assertTrue(reservation.allowed());
        service.settle(reservation, 0, false, "stub", "mock-model");
        Map<String, Object> failed = service.get(queueId);
        assertEquals("FAILED", failed.get("status"));
        assertEquals("AI_UNAVAILABLE", failed.get("reason_code"));
        assertEquals(0, jdbc.queryForObject("SELECT settled_articles FROM ai_budget_usage WHERE scope_type='GLOBAL'",
                Integer.class));
        assertEquals(0, failed.get("actual_tokens"));
        assertEquals("可恢复正文", jdbc.queryForObject("SELECT raw_text FROM source_document WHERE id=?", String.class, document));
        service.approve(queueId, admin());
        assertEquals("QUEUED", service.get(queueId).get("status"));
    }

    private void reserveQueue(CountDownLatch start, long queueId, AtomicInteger allowed) {
        try {
            start.await();
            if (service.reserveQueue(queueId).allowed()) allowed.incrementAndGet();
        } catch (Exception ignored) {
            // A database lock loser is also a safe non-overspend outcome.
        }
    }

    private AiQueueService.Reservation reserveAutomatically(long document, Long sourceId) {
        return service.reserveQueue(approvedQueue(document, sourceId));
    }

    private long approvedQueue(long document, Long sourceId) {
        long queueId = ((Number) service.enqueue(document, sourceId, null).get("id")).longValue();
        service.approve(queueId, admin());
        return queueId;
    }

    private long insertCrawlJob(long source, Long parentId, String url) {
        jdbc.update("INSERT INTO crawl_job(parent_job_id,source_registry_id,original_url,canonical_url,status,"
                + "trigger_type,processing_stage) VALUES (?,?,?,?,'SUCCESS','MANUAL','IMPORT')",
                parentId, source, url, url);
        return jdbc.queryForObject("SELECT MAX(id) FROM crawl_job", Long.class);
    }

    private long insertDocument(String title, String body) {
        long user = jdbc.queryForObject("SELECT id FROM staff_user WHERE username='platform_admin'", Long.class);
        long org = jdbc.queryForObject("SELECT organization_id FROM staff_user WHERE id=?", Long.class, user);
        jdbc.update("INSERT INTO source_document(organization_id,title,raw_text,processing_status,created_by,source_type) "
                + "VALUES (?,?,?,'UPLOADED',?,'PDF')", org, title, body, user);
        return jdbc.queryForObject("SELECT MAX(id) FROM source_document", Long.class);
    }

    private long insertWebDocument(String title, String body, long source) {
        long id = insertDocument(title, body);
        jdbc.update("UPDATE source_document SET source_type='WEB_ARTICLE' WHERE id=?", id);
        jdbc.update("INSERT INTO crawl_job(source_registry_id,document_id,original_url,status,trigger_type,processing_stage) "
                + "VALUES (?,?,'https://example.invalid/test','SUCCESS','MANUAL','IMPORT')", source, id);
        return id;
    }

    private long source() {
        return jdbc.queryForObject("SELECT id FROM source_registry ORDER BY id LIMIT 1", Long.class);
    }

    private AuthUser admin() {
        Map<String, Object> user = jdbc.queryForMap("SELECT u.id,u.organization_id,u.username,u.display_name,u.role,o.name "
                + "FROM staff_user u JOIN organization o ON o.id=u.organization_id WHERE u.username='platform_admin'");
        return new AuthUser(((Number) user.get("id")).longValue(), ((Number) user.get("organization_id")).longValue(),
                String.valueOf(user.get("username")), String.valueOf(user.get("display_name")),
                String.valueOf(user.get("role")), String.valueOf(user.get("name")));
    }
}
