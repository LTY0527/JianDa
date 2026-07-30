package cn.jianda.collector;

import cn.jianda.common.ApiResponse;
import cn.jianda.security.UserContext;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cover-backfill")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class CoverBackfillController {
    private final CoverBackfillService service;

    public CoverBackfillController(CoverBackfillService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(
            @RequestBody(required = false) CoverBackfillService.BackfillFilter filter) {
        return ApiResponse.ok(service.preview(filter));
    }

    @PostMapping("/execute")
    public ApiResponse<Map<String, Object>> execute(
            @RequestBody(required = false) CoverBackfillService.BackfillFilter filter) {
        return ApiResponse.ok(service.execute(filter, UserContext.current()));
    }

    @PostMapping("/jobs")
    public ApiResponse<Map<String, Object>> startJob(
            @RequestBody(required = false)
            CoverBackfillService.BackfillFilter filter) {
        return ApiResponse.ok(service.startJob(filter, UserContext.current()));
    }

    @GetMapping("/jobs/{jobId}")
    public ApiResponse<Map<String, Object>> job(@PathVariable long jobId) {
        return ApiResponse.ok(service.job(jobId));
    }

    @PostMapping("/jobs/{jobId}/retry/{documentId}")
    public ApiResponse<Map<String, Object>> retry(
            @PathVariable long jobId, @PathVariable long documentId) {
        return ApiResponse.ok(service.retry(jobId, documentId));
    }
}
