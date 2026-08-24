package cn.jianda.collector;

import cn.jianda.common.ApiResponse;
import cn.jianda.security.UserContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/source-registries")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class SourceRegistryController {
    private final SourceRegistryService service;
    private final ArticleDiscoveryService discoveryService;
    private final ArticleDiscoveryJobService discoveryJobService;
    private final WebArticleService webArticleService;
    private final BatchArticleImportJobService batchImportJobService;

    public SourceRegistryController(SourceRegistryService service, ArticleDiscoveryService discoveryService,
                                    ArticleDiscoveryJobService discoveryJobService,
                                    WebArticleService webArticleService,
                                    BatchArticleImportJobService batchImportJobService) {
        this.service = service;
        this.discoveryService = discoveryService;
        this.discoveryJobService = discoveryJobService;
        this.webArticleService = webArticleService;
        this.batchImportJobService = batchImportJobService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @Valid @RequestBody SourceRegistryService.SourceConfiguration request) {
        return ApiResponse.ok(service.create(request, UserContext.current()));
    }

    @PostMapping("/quick-preview")
    public ApiResponse<Map<String, Object>> quickPreview(@Valid @RequestBody ControlledUrlRequest request) {
        return ApiResponse.ok(webArticleService.previewUnregistered(request.url()));
    }

    @PostMapping("/quick-confirm")
    public ApiResponse<Map<String, Object>> quickConfirm(@Valid @RequestBody QuickConfirmRequest request) {
        Map<String, Object> preview = webArticleService.previewUnregistered(request.url());
        SourceRegistryService.QuickSourceConfirmation confirmation =
                new SourceRegistryService.QuickSourceConfirmation(
                        request.sourceName(), request.sourceType(), request.verificationNote(),
                        request.officialConfirmed(), request.mode(), request.imageUsagePolicy(),
                        request.imageUsageBasis(), request.autoApproveImages(),
                        request.imageCacheAllowed(), request.continueImport());
        Map<String, Object> source = service.confirmQuickSource(confirmation, preview, UserContext.current());
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("source", source);
        if (Boolean.TRUE.equals(request.continueImport())) {
            result.put("imported", webArticleService.importApprovedArticle(
                    request.url(), ((Number) source.get("id")).longValue(), UserContext.current()));
        }
        return ApiResponse.ok(result);
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable long id,
            @Valid @RequestBody SourceRegistryService.SourceConfiguration request) {
        return ApiResponse.ok(service.update(id, request, UserContext.current()));
    }

    @PutMapping("/{id}/enabled")
    public ApiResponse<Map<String, Object>> enabled(@PathVariable long id, @RequestBody EnabledRequest request) {
        return ApiResponse.ok(service.setEnabled(id, request.enabled(), UserContext.current()));
    }

    @PutMapping("/{id}/image-candidates-enabled")
    public ApiResponse<Map<String, Object>> imageCandidatesEnabled(
            @PathVariable long id, @RequestBody EnabledRequest request) {
        return ApiResponse.ok(service.setImageCandidatesEnabled(id, request.enabled(), UserContext.current()));
    }

    @PutMapping("/{id}/auto-crawl-enabled")
    public ApiResponse<Map<String, Object>> autoCrawlEnabled(
            @PathVariable long id, @RequestBody EnabledRequest request) {
        return ApiResponse.ok(service.setAutoCrawlEnabled(id, request.enabled(), UserContext.current()));
    }

    @PostMapping("/{id}/discover")
    public ApiResponse<Map<String, Object>> discover(@PathVariable long id, @RequestBody DiscoveryRequest request) {
        return ApiResponse.ok(discoveryService.discover(id, request.method(), request.entryUrl(),
                new ArticleDiscoveryService.DiscoveryOptions(
                        request.recentDays(), request.maxArticles(), request.includeKeywords(),
                        request.excludeKeywords(), request.onlyUnimported())));
    }

    @PostMapping("/{id}/discover-jobs")
    public ApiResponse<Map<String, Object>> startDiscoveryJob(
            @PathVariable long id, @RequestBody DiscoveryRequest request) {
        return ApiResponse.ok(discoveryJobService.start(id, request.method(), request.entryUrl(),
                new ArticleDiscoveryService.DiscoveryOptions(
                        request.recentDays(), request.maxArticles(), request.includeKeywords(),
                        request.excludeKeywords(), request.onlyUnimported()), UserContext.current()));
    }

    @GetMapping("/discover-jobs/{jobId}")
    public ApiResponse<Map<String, Object>> discoveryJob(@PathVariable long jobId) {
        return ApiResponse.ok(discoveryJobService.detail(jobId));
    }

    @PostMapping("/{id}/shadow")
    public ApiResponse<Map<String, Object>> shadow(
            @PathVariable long id, @Valid @RequestBody ControlledUrlRequest request) {
        Map<String, Object> preview = webArticleService.preview(request.url());
        service.assertPreviewBelongsTo(id, preview);
        return ApiResponse.ok(preview);
    }

    @PostMapping("/{id}/collect")
    public ApiResponse<Map<String, Object>> collect(
            @PathVariable long id, @Valid @RequestBody ControlledUrlRequest request) {
        Map<String, Object> preview = webArticleService.preview(request.url());
        service.assertPreviewBelongsTo(id, preview);
        return ApiResponse.ok(webArticleService.importArticle(request.url(), UserContext.current()));
    }

    @PostMapping("/{id}/collect-batch")
    public ResponseEntity<ApiResponse<Map<String, Object>>> collectBatch(
            @PathVariable long id, @Valid @RequestBody BatchControlledUrlRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(
                batchImportJobService.start(id, request.urls(), UserContext.current())));
    }

    @GetMapping("/import-jobs/{jobId}")
    public ApiResponse<Map<String, Object>> importJob(@PathVariable long jobId) {
        return ApiResponse.ok(batchImportJobService.detail(jobId));
    }

    public record EnabledRequest(boolean enabled) {}
    public record DiscoveryRequest(String method, String entryUrl, Integer recentDays, Integer maxArticles,
            String includeKeywords, String excludeKeywords, Boolean onlyUnimported) {}
    public record ControlledUrlRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 1500)
            String url) {}

    public record BatchControlledUrlRequest(
            @jakarta.validation.constraints.NotEmpty
            @jakarta.validation.constraints.Size(max = 100)
            List<@jakarta.validation.constraints.NotBlank
                    @jakarta.validation.constraints.Size(max = 1500) String> urls) {}

    public record QuickConfirmRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 1500)
            String url,
            String sourceName,
            String sourceType,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 1000)
            String verificationNote,
            boolean officialConfirmed,
            String mode,
            String imageUsagePolicy,
            String imageUsageBasis,
            Boolean autoApproveImages,
            Boolean imageCacheAllowed,
            Boolean continueImport) {}
}
