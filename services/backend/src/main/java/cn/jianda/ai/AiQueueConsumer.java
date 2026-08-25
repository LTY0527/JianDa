package cn.jianda.ai;

import cn.jianda.document.DocumentService;
import cn.jianda.security.AuthUser;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiQueueConsumer {
    private final AiQueueService queueService;
    private final DocumentService documentService;
    private final boolean schedulerEnabled;
    private final boolean autoAiEnabled;
    private final int batchSize;

    public AiQueueConsumer(AiQueueService queueService, DocumentService documentService,
            @Value("${jianda.crawl.scheduler-enabled:false}") boolean schedulerEnabled,
            @Value("${jianda.crawl.auto-ai-enabled:false}") boolean autoAiEnabled,
            @Value("${jianda.crawl.ai-consumer-batch-size:2}") int batchSize) {
        this.queueService = queueService;
        this.documentService = documentService;
        this.schedulerEnabled = schedulerEnabled;
        this.autoAiEnabled = autoAiEnabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 20));
    }

    @Scheduled(fixedDelayString = "${jianda.crawl.ai-consumer-delay-ms:60000}")
    public void consume() {
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
                // A failed item remains FAILED for explicit human retry; it is never auto-requeued.
            }
        }
    }
}
