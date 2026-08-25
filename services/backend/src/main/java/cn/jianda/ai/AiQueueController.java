package cn.jianda.ai;

import cn.jianda.common.ApiResponse;
import cn.jianda.document.DocumentService;
import cn.jianda.security.UserContext;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-queue")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AiQueueController {
    private final AiQueueService queueService;
    private final DocumentService documentService;

    public AiQueueController(AiQueueService queueService, DocumentService documentService) {
        this.queueService = queueService;
        this.documentService = documentService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(queueService.list(status));
    }

    @PostMapping("/{queueId}/approve")
    public ApiResponse<Map<String, Object>> approve(@PathVariable long queueId) {
        return ApiResponse.ok(queueService.approve(queueId, UserContext.current()));
    }

    @PostMapping("/{queueId}/execute")
    public ApiResponse<Map<String, Object>> execute(@PathVariable long queueId) {
        return ApiResponse.ok(documentService.processQueued(queueId, UserContext.current()));
    }

    @PostMapping("/reconcile")
    public ApiResponse<Map<String, Integer>> reconcile() {
        return ApiResponse.ok(queueService.reconcile(UserContext.current()));
    }

    @PostMapping("/{queueId}/retry")
    public ApiResponse<Map<String, Object>> retry(@PathVariable long queueId) {
        return ApiResponse.ok(queueService.retry(queueId, UserContext.current()));
    }
}
