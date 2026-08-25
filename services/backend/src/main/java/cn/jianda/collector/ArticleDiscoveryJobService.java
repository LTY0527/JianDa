package cn.jianda.collector;

import cn.jianda.common.BusinessException;
import cn.jianda.security.AuthUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ArticleDiscoveryJobService {
    private final ArticleDiscoveryService discoveryService;
    private final CrawlTaskService taskService;
    private final WebArticleService webArticleService;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public ArticleDiscoveryJobService(ArticleDiscoveryService discoveryService, CrawlTaskService taskService,
            WebArticleService webArticleService, ObjectMapper objectMapper,
            @Qualifier("documentProcessingExecutor") Executor executor) {
        this.discoveryService = discoveryService;
        this.taskService = taskService;
        this.webArticleService = webArticleService;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public Map<String, Object> start(long sourceId, String method, String entryUrl,
            ArticleDiscoveryService.DiscoveryOptions options, AuthUser user) {
        long jobId = taskService.createBatch(sourceId, entryUrl, "MANUAL", method, user, null);
        Map<String, Object> existing = taskService.detail(jobId);
        if (!"PENDING".equals(existing.get("status"))) {
            return result(existing, true);
        }
        executor.execute(() -> run(jobId, sourceId, method, entryUrl, options));
        return result(taskService.detail(jobId), false);
    }

    public Map<String, Object> detail(long jobId) {
        return result(taskService.detail(jobId), false);
    }

    @Scheduled(fixedDelayString = "${jianda.crawl.retry-consumer-delay-ms:60000}")
    public void consumePendingRetries() {
        for (Long jobId : taskService.pendingRetryJobs()) {
            try {
                dispatchRetry(jobId, taskService.retryUser(jobId));
            } catch (RuntimeException ignored) {
                // Another consumer or administrator may have changed the recoverable DB state.
            }
        }
    }

    public Map<String, Object> retryError(long errorId, AuthUser user) {
        long jobId = taskService.retryError(errorId, user);
        dispatchRetry(jobId, user);
        Map<String, Object> response = result(taskService.detail(jobId), false);
        response.put("jobId", jobId);
        return response;
    }

    public List<Long> retryFailures(long jobId, AuthUser user) {
        List<Long> jobIds = taskService.retryBatch(jobId, user);
        jobIds.forEach(retryJobId -> dispatchRetry(retryJobId, user));
        return jobIds;
    }

    private void dispatchRetry(long jobId, AuthUser user) {
        Map<String, Object> job = taskService.detail(jobId);
        String url = text(job.get("original_url"));
        String stage = text(job.get("processing_stage"));
        if ("DISCOVERY".equalsIgnoreCase(stage)) {
            executor.execute(() -> run(jobId,
                    ((Number) job.get("source_registry_id")).longValue(),
                    text(job.get("discovery_method")), url, null));
            return;
        }
        executor.execute(() -> runImportRetry(jobId, url, user));
    }

    private void runImportRetry(long jobId, String url, AuthUser user) {
        String owner = "article-retry-" + UUID.randomUUID();
        boolean started = false;
        try {
            taskService.start(jobId, owner);
            started = true;
            webArticleService.importArticle(url, user);
            taskService.finish(jobId, owner, new CrawlTaskService.Counts(1, 1, 0, 0, 0), List.of());
        } catch (BusinessException exception) {
            if (started && exception.getCode() == 409) {
                taskService.finish(jobId, owner, new CrawlTaskService.Counts(1, 0, 1, 0, 0), List.of());
            } else if (started) {
                finishFailure(jobId, owner, url, "IMPORT", "IMPORT_FAILED", exception.getMessage(),
                        exception.getCode() >= 500, true);
            }
        } catch (RuntimeException exception) {
            finishFailure(jobId, owner, url, "IMPORT", "UNEXPECTED_ERROR",
                    "导入过程意外中断，系统可稍后重试", true, started);
        }
    }

    private void run(long jobId, long sourceId, String method, String entryUrl,
            ArticleDiscoveryService.DiscoveryOptions options) {
        String owner = "manual-discovery-" + UUID.randomUUID();
        boolean started = false;
        try {
            taskService.start(jobId, owner);
            started = true;
            taskService.updateDiscoveryProgress(jobId, owner, "CONNECTING", "正在连接官网");
            Map<String, Object> discovered = discoveryService.discover(sourceId, method, entryUrl, options);
            taskService.updateDiscoveryProgress(jobId, owner, "IDENTIFYING", "正在识别文章并去重");
            taskService.saveDiscoveryResult(jobId, owner, objectMapper.writeValueAsString(discovered));
            int count = discovered.get("candidates") instanceof List<?> list ? list.size() : 0;
            int duplicates = number(discovered.get("duplicateCount"));
            taskService.finish(jobId, owner,
                    new CrawlTaskService.Counts(count, Math.max(0, count - duplicates), duplicates, 0, 0), List.of());
        } catch (DiscoveryFailureException exception) {
            finishFailure(jobId, owner, entryUrl, "DISCOVERY", exception.reasonCode(), exception.getMessage(),
                    exception.retryable(), started);
        } catch (JsonProcessingException exception) {
            finishFailure(jobId, owner, entryUrl, "DISCOVERY", "PARSER_UNSUPPORTED", "检查结果暂时无法保存", false, started);
        } catch (BusinessException exception) {
            if (started && exception.getCode() != 409) {
                finishFailure(jobId, owner, entryUrl, "DISCOVERY", "PARSER_UNSUPPORTED", exception.getMessage(), false, true);
            }
        } catch (RuntimeException exception) {
            finishFailure(jobId, owner, entryUrl, "DISCOVERY", "UNEXPECTED_ERROR",
                    "检查过程意外中断，系统可稍后重试", true, started);
        }
    }

    private void finishFailure(long jobId, String owner, String url, String stage, String code, String message,
            boolean retryable, boolean started) {
        if (!started) return;
        try {
            taskService.finish(jobId, owner, new CrawlTaskService.Counts(0, 0, 0, 0, 1),
                    List.of(new CrawlTaskService.Failure(url, stage, code, message, retryable)));
        } catch (BusinessException ignored) {
            // A user cancellation wins over late network completion.
        }
    }

    private Map<String, Object> result(Map<String, Object> raw, boolean existing) {
        Map<String, Object> value = new LinkedHashMap<>(raw);
        value.put("existing", existing);
        Object json = value.remove("discovery_result_json");
        if (json != null && !String.valueOf(json).isBlank()) {
            try {
                value.put("discoveryResult", objectMapper.readValue(
                        String.valueOf(json), new TypeReference<Map<String, Object>>() {}));
            } catch (JsonProcessingException ignored) {
                value.put("resultUnavailable", true);
            }
        }
        return value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int number(Object value) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }
}
