package cn.jianda.ai;

import cn.jianda.document.DocumentService;
import cn.jianda.security.AuthUser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiQueueConsumer {
    private final AiQueueService queueService;
    private final DocumentService documentService;
    private final JdbcTemplate jdbc;
    private final boolean schedulerEnabled;
    private final boolean autoAiEnabled;
    private final int batchSize;
    private static final Set<String> STALE_STAGES = Set.of("QUEUED", "PREPARING");
    private static final int STAGE_STALE_MINUTES = 90;

    public AiQueueConsumer(AiQueueService queueService, DocumentService documentService,
            JdbcTemplate jdbc,
            @Value("${jianda.crawl.scheduler-enabled:false}") boolean schedulerEnabled,
            @Value("${jianda.crawl.auto-ai-enabled:false}") boolean autoAiEnabled,
            @Value("${jianda.crawl.ai-consumer-batch-size:2}") int batchSize) {
        this.queueService = queueService;
        this.documentService = documentService;
        this.jdbc = jdbc;
        this.schedulerEnabled = schedulerEnabled;
        this.autoAiEnabled = autoAiEnabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 20));
    }

    private int reconcileStaleProcessingJobs() {
        List<Long> staleIds = jdbc.queryForList(
                "SELECT id FROM processing_job WHERE status='PROCESSING' "
                        + "AND stage IN ('QUEUED','PREPARING') "
                        + "AND updated_at < TIMESTAMPADD(MINUTE,-?,CURRENT_TIMESTAMP)",
                Long.class, STAGE_STALE_MINUTES);
        if (staleIds.isEmpty()) return 0;
        int changed = 0;
        for (Long id : staleIds) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT p.id,p.document_id FROM processing_job p WHERE p.id=? AND p.status='PROCESSING' "
                            + "AND p.stage IN ('QUEUED','PREPARING') "
                            + "AND p.updated_at < TIMESTAMPADD(MINUTE,-?,CURRENT_TIMESTAMP) FOR UPDATE",
                    id, STAGE_STALE_MINUTES);
            if (row == null || row.isEmpty()) continue;
            long documentId = ((Number) row.get("document_id")).longValue();
            int updated = jdbc.update(
                    "UPDATE processing_job SET status='FAILED_RETRYABLE',stage='QUEUE_TIMEOUT_STALE',"
                            + "last_failed_stage=COALESCE(stage,'UNKNOWN'),reason_code='QUEUE_TIMEOUT_STALE',"
                            + "error_message='在处理队列中等待过久，可安全重试，上下文已保留',"
                            + "finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE id=? AND status='PROCESSING'",
                    id);
            if (updated > 0) {
                jdbc.update("UPDATE source_document SET processing_status='FAILED',updated_at=CURRENT_TIMESTAMP "
                        + "WHERE id=? AND processing_status='PROCESSING'", documentId);
                changed++;
            }
        }
        return changed;
    }

    @Scheduled(fixedDelayString = "${jianda.crawl.ai-consumer-delay-ms:60000}")
    public void consume() {
        reconcileStaleProcessingJobs();
        if (!schedulerEnabled || !autoAiEnabled) return;
        List<Map<String, Object>> queued = queueService.list(AiQueueService.QUEUED).stream()
                .filter(row -> row.get("approved_at") == null)
                .limit(batchSize)
                .toList();
        for (Map<String, Object> item : queued) {
            try {
                long queueId = ((Number) item.get("id")).longValue();
                long documentId = ((Number) item.get("document_id")).longValue();
                Map<String, Object> owner = queueService.automationOwner(documentId);
                AuthUser system = new AuthUser(((Number) owner.get("id")).longValue(),
                        ((Number) owner.get("organization_id")).longValue(),
                        "crawl-ai-scheduler", "采集 AI 调度器", "PLATFORM_ADMIN",
                        String.valueOf(owner.get("organization_name")));
                documentService.processQueued(queueId, system);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
