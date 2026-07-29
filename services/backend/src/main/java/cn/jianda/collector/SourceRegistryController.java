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

@RestController
@RequestMapping("/api/source-registries")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class SourceRegistryController {
    private final SourceRegistryService service;
    private final ArticleDiscoveryService discoveryService;
    private final WebArticleService webArticleService;

    public SourceRegistryController(SourceRegistryService service, ArticleDiscoveryService discoveryService,
                                    WebArticleService webArticleService) {
        this.service = service;
        this.discoveryService = discoveryService;
        this.webArticleService = webArticleService;
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

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable long id,
            @Valid @RequestBody SourceRegistryService.SourceConfiguration request) {
        return ApiResponse.ok(service.update(id, request, UserContext.current()));
    }

    @PutMapping("/{id}/enabled")
    public ApiResponse<Map<String, Object>> enabled(@PathVariable long id, @RequestBody EnabledRequest request) {
        return ApiResponse.ok(service.setEnabled(id, request.enabled(), UserContext.current()));
    }

    @PostMapping("/{id}/discover")
    public ApiResponse<Map<String, Object>> discover(@PathVariable long id, @RequestBody DiscoveryRequest request) {
        return ApiResponse.ok(discoveryService.discover(id, request.method(), request.entryUrl()));
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

    public record EnabledRequest(boolean enabled) {}
    public record DiscoveryRequest(String method, String entryUrl) {}
    public record ControlledUrlRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 1500)
            String url) {}
}
