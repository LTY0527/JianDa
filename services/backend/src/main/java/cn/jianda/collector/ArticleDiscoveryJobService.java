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
import org.springframework.stereotype.Service;

@Service
public class ArticleDiscoveryJobService {
    private final ArticleDiscoveryService discoveryService;
    private final CrawlTaskService taskService;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public ArticleDiscoveryJobService(ArticleDiscoveryService discoveryService, CrawlTaskService taskService,
            ObjectMapper objectMapper, @Qualifier("documentProcessingExecutor") Executor executor) {
        this.discoveryService = discoveryService;
        this.taskService = taskService;
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
            finishFailure(jobId, owner, entryUrl, exception.reasonCode(), exception.getMessage(),
                    exception.retryable(), started);
        } catch (JsonProcessingException exception) {
            finishFailure(jobId, owner, entryUrl, "PARSER_UNSUPPORTED", "检查结果暂时无法保存", false, started);
        } catch (BusinessException exception) {
            if (started && exception.getCode() != 409) {
                finishFailure(jobId, owner, entryUrl, "PARSER_UNSUPPORTED", exception.getMessage(), false, true);
            }
        } catch (RuntimeException exception) {
            finishFailure(jobId, owner, entryUrl, "READ_TIMEOUT", "检查过程意外中断，系统可稍后重试", true, started);
        }
    }

    private void finishFailure(long jobId, String owner, String url, String code, String message,
            boolean retryable, boolean started) {
        if (!started) return;
        try {
            taskService.finish(jobId, owner, new CrawlTaskService.Counts(0, 0, 0, 0, 1),
                    List.of(new CrawlTaskService.Failure(url, "DISCOVERY", code, message, retryable)));
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

    private static int number(Object value) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }
}
