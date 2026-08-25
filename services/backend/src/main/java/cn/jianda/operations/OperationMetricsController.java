package cn.jianda.operations;

import cn.jianda.common.ApiResponse;
import java.util.Map;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/operation-metrics")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class OperationMetricsController {
    private final OperationMetricsService service;

    public OperationMetricsController(OperationMetricsService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> current() {
        return ApiResponse.ok(service.current());
    }

    @GetMapping("/assistant-events")
    public ApiResponse<List<Map<String, Object>>> assistantEvents(
            @RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(service.assistantEvents(limit));
    }
}
