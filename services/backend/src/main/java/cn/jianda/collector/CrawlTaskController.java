package cn.jianda.collector;

import cn.jianda.common.ApiResponse;
import cn.jianda.security.UserContext;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/crawl-tasks")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class CrawlTaskController {
    private final CrawlTaskService service;
    private final CrawlScheduler scheduler;

    public CrawlTaskController(CrawlTaskService service, CrawlScheduler scheduler) {
        this.service = service;
        this.scheduler = scheduler;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> jobs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long sourceId) {
        return ApiResponse.ok(service.jobs(status, sourceId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable long id) {
        service.cancel(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/errors/{errorId}/retry")
    public ApiResponse<Map<String, Object>> retryError(@PathVariable long errorId) {
        return ApiResponse.ok(Map.of("jobId", service.retryError(errorId, UserContext.current())));
    }

    @PostMapping("/{id}/retry-failures")
    public ApiResponse<Map<String, Object>> retryFailures(@PathVariable long id) {
        List<Long> jobIds = service.retryBatch(id, UserContext.current());
        return ApiResponse.ok(Map.of("jobIds", jobIds, "count", jobIds.size()));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateRequest request) {
        long jobId = service.createBatch(request.sourceId(), request.entryUrl(), request.triggerType(),
                request.discoveryMethod(), UserContext.current(), null);
        return ApiResponse.ok(Map.of("jobId", jobId));
    }

    @PostMapping("/scheduler/sources/{sourceId}/run-now")
    public ApiResponse<Map<String, Object>> runScheduledSource(@PathVariable long sourceId) {
        return ApiResponse.ok(scheduler.runSourceNow(sourceId));
    }

    public record CreateRequest(long sourceId, String entryUrl, String triggerType, String discoveryMethod) {}
}
