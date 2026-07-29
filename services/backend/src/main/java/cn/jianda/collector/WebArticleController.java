package cn.jianda.collector;

import cn.jianda.common.ApiResponse;
import cn.jianda.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/web-articles")
public class WebArticleController {
    private final WebArticleService service;
    private final CrawlTaskService taskService;

    public WebArticleController(WebArticleService service, CrawlTaskService taskService) {
        this.service = service;
        this.taskService = taskService;
    }

    @GetMapping("/sources")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<List<Map<String, Object>>> sources() {
        return ApiResponse.ok(service.registries());
    }

    @GetMapping("/jobs")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<List<Map<String, Object>>> jobs() {
        return ApiResponse.ok(taskService.jobs(null, null));
    }

    @PostMapping("/jobs/{jobId}/stop")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<Void> stopJob(@PathVariable long jobId) {
        taskService.cancel(jobId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ApiResponse<Map<String, Object>> preview(@Valid @RequestBody UrlRequest request) {
        return ApiResponse.ok(service.preview(request.url()));
    }

    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ApiResponse<Map<String, Object>> importArticle(@Valid @RequestBody UrlRequest request) {
        return ApiResponse.ok(service.importArticle(request.url(), UserContext.current()));
    }

    @PostMapping("/{documentId}/cover/confirm")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','REVIEWER')")
    public ApiResponse<Void> confirmCover(@PathVariable long documentId) {
        service.confirmCover(documentId, UserContext.current());
        return ApiResponse.ok(null);
    }

    @PostMapping("/{documentId}/cover/category-default")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','REVIEWER')")
    public ApiResponse<Void> categoryDefault(@PathVariable long documentId) {
        service.useCategoryDefault(documentId, UserContext.current());
        return ApiResponse.ok(null);
    }

    @PostMapping("/{documentId}/cover/article-image")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','REVIEWER')")
    public ApiResponse<Void> articleImage(
            @PathVariable long documentId, @Valid @RequestBody CoverRequest request) {
        service.selectArticleCover(documentId, request.imageUrl(), UserContext.current());
        return ApiResponse.ok(null);
    }

    @PostMapping("/{documentId}/recrawl")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ApiResponse<Map<String, Object>> recrawl(@PathVariable long documentId) {
        return ApiResponse.ok(service.recrawl(documentId, UserContext.current()));
    }

    public record UrlRequest(@NotBlank String url) {}
    public record CoverRequest(@NotBlank String imageUrl) {}
}
