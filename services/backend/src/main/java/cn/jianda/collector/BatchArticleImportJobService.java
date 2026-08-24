package cn.jianda.collector;

import cn.jianda.common.BusinessException;
import cn.jianda.security.AuthUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class BatchArticleImportJobService {
    private final CrawlTaskService tasks;
    private final SourceRegistryService registries;
    private final WebArticleService articles;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public BatchArticleImportJobService(CrawlTaskService tasks, SourceRegistryService registries,
            WebArticleService articles, ObjectMapper objectMapper,
            @Qualifier("documentProcessingExecutor") Executor executor) {
        this.tasks = tasks;
        this.registries = registries;
        this.articles = articles;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public Map<String, Object> start(long sourceId, List<String> rawUrls, AuthUser user) {
        List<String> urls = rawUrls.stream().map(String::trim).filter(value -> !value.isBlank())
                .distinct().limit(100).toList();
        if (urls.isEmpty()) throw new BusinessException(400, "请至少选择一篇内容");
        long jobId = tasks.createBatch(sourceId, registries.get(sourceId).get("section_url") == null
                ? "batch-import" : String.valueOf(registries.get(sourceId).get("section_url")),
                "MANUAL", "BATCH_IMPORT", user, null);
        executor.execute(() -> run(jobId, sourceId, urls, user));
        return Map.of("jobId", jobId, "status", "PENDING", "total", urls.size());
    }

    public Map<String, Object> detail(long jobId) {
        Map<String, Object> result = new LinkedHashMap<>(tasks.detail(jobId));
        Object json = result.remove("discovery_result_json");
        if (json != null && !String.valueOf(json).isBlank()) {
            try {
                result.put("result", objectMapper.readValue(String.valueOf(json), Map.class));
            } catch (JsonProcessingException ignored) {
                result.put("resultUnavailable", true);
            }
        }
        return result;
    }

    private void run(long jobId, long sourceId, List<String> urls, AuthUser user) {
        String owner = "batch-import-" + UUID.randomUUID();
        int added = 0;
        int duplicates = 0;
        boolean running = false;
        boolean finished = false;
        List<Map<String, Object>> imported = new ArrayList<>();
        List<CrawlTaskService.Failure> failures = new ArrayList<>();
        try {
            tasks.start(jobId, owner);
            running = true;
            for (int index = 0; index < urls.size(); index++) {
                String url = urls.get(index);
                tasks.updateImportProgress(jobId, owner, urls.size(), index, added, duplicates,
                        failures.size(), "正在抓取第 " + (index + 1) + "/" + urls.size() + " 篇");
                try {
                    Map<String, Object> preview = articles.preview(url);
                    registries.assertPreviewBelongsTo(sourceId, preview);
                    Map<String, Object> result = articles.importArticle(url, user);
                    imported.add(result);
                    added++;
                } catch (BusinessException exception) {
                    if (exception.getCode() == 409) {
                        duplicates++;
                    } else {
                        failures.add(new CrawlTaskService.Failure(
                                url, "BATCH_IMPORT", "IMPORT_FAILED", exception.getMessage(), exception.getCode() >= 500));
                    }
                } catch (RuntimeException exception) {
                    failures.add(new CrawlTaskService.Failure(
                            url, "BATCH_IMPORT", "UNEXPECTED_ERROR", "该文章暂时无法加入，可稍后重试", true));
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("imported", imported);
            result.put("importedCount", added);
            result.put("duplicateCount", duplicates);
            result.put("failedCount", failures.size());
            tasks.saveDiscoveryResult(jobId, owner, objectMapper.writeValueAsString(result));
            tasks.finish(jobId, owner,
                    new CrawlTaskService.Counts(urls.size(), added, duplicates, 0, failures.size()), failures);
            finished = true;
        } catch (JsonProcessingException exception) {
            tasks.finish(jobId, owner, new CrawlTaskService.Counts(urls.size(), added, duplicates, 0, 1),
                    List.of(new CrawlTaskService.Failure("batch-import", "BATCH_IMPORT", "RESULT_SAVE_FAILED",
                            "导入已执行，但结果摘要保存失败", true)));
            finished = true;
        } catch (RuntimeException exception) {
            if (running && !finished) {
                try {
                    tasks.finish(jobId, owner,
                            new CrawlTaskService.Counts(urls.size(), added, duplicates, 0, 1),
                            List.of(new CrawlTaskService.Failure("batch-import", "BATCH_IMPORT",
                                    "BATCH_JOB_FAILED", "批量加入任务异常结束，可稍后重试", true)));
                } catch (RuntimeException ignored) {
                    // The task may have been cancelled or ownership may have changed concurrently.
                }
            }
        }
    }
}
