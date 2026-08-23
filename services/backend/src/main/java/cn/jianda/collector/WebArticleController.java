package cn.jianda.collector;

import cn.jianda.common.ApiResponse;
import cn.jianda.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    private final ImageCandidateService imageCandidateService;

    public WebArticleController(WebArticleService service, CrawlTaskService taskService,
                                ImageCandidateService imageCandidateService) {
        this.service = service;
        this.taskService = taskService;
        this.imageCandidateService = imageCandidateService;
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

    @PostMapping("/preview-any")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ApiResponse<Map<String, Object>> previewAny(
            @Valid @RequestBody UrlRequest request) {
        return ApiResponse.ok(service.previewAny(request.url()));
    }

    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ApiResponse<Map<String, Object>> importArticle(@Valid @RequestBody UrlRequest request) {
        return ApiResponse.ok(service.importArticle(request.url(), UserContext.current()));
    }

    @PostMapping("/import-once")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ApiResponse<Map<String, Object>> importOnce(
            @Valid @RequestBody OneOffImportRequest request) {
        return ApiResponse.ok(service.importOneOffArticle(
                request.url(), Boolean.TRUE.equals(request.canonicalConfirmed()),
                UserContext.current()));
    }

    @PostMapping("/import-pasted")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ApiResponse<Map<String, Object>> importPasted(
            @Valid @RequestBody PastedImportRequest request) {
        return ApiResponse.ok(service.importPastedArticle(
                request.url(), request.title(), request.sourceName(),
                request.body(), request.contentKind(), UserContext.current()));
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

    @PostMapping("/{documentId}/region/sync")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ApiResponse<Map<String, Object>> syncRegion(@PathVariable long documentId) {
        return ApiResponse.ok(service.syncRegionFromRegistry(documentId, UserContext.current()));
    }

    @GetMapping("/{documentId}/image-candidates")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','REVIEWER')")
    public ApiResponse<List<Map<String, Object>>> imageCandidates(@PathVariable long documentId) {
        return ApiResponse.ok(imageCandidateService.list(documentId, UserContext.current()));
    }

    @PostMapping("/image-candidates/{candidateId}/approve")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','REVIEWER')")
    public ApiResponse<Void> approveCandidate(@PathVariable long candidateId,
            @Valid @RequestBody CandidateApprovalRequest request) {
        imageCandidateService.approve(candidateId, request.sourceName(), request.usageBasis(), UserContext.current());
        return ApiResponse.ok(null);
    }

    @PostMapping("/image-candidates/{candidateId}/reject")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN','REVIEWER')")
    public ApiResponse<Void> rejectCandidate(@PathVariable long candidateId,
            @Valid @RequestBody CandidateRejectionRequest request) {
        imageCandidateService.reject(candidateId, request.reason(), UserContext.current());
        return ApiResponse.ok(null);
    }

    public record UrlRequest(@NotBlank String url) {}
    public record OneOffImportRequest(
            @NotBlank String url, Boolean canonicalConfirmed) {}
    public record PastedImportRequest(
            @NotBlank String url,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 200) String sourceName,
            @NotBlank @Size(max = 200000) String body,
            @Size(max = 60) String contentKind) {}
    public record CoverRequest(@NotBlank String imageUrl) {}
    public record CandidateApprovalRequest(@NotBlank String sourceName, @NotBlank String usageBasis) {}
    public record CandidateRejectionRequest(@NotBlank String reason) {}
}
