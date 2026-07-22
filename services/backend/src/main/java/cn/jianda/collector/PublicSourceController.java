package cn.jianda.collector;

import cn.jianda.common.ApiResponse;
import cn.jianda.document.DocumentService;
import cn.jianda.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public-sources")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PublicSourceController {
    private final ContentImportService importService;
    private final DocumentService documentService;

    public PublicSourceController(ContentImportService importService, DocumentService documentService) {
        this.importService = importService;
        this.documentService = documentService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> sources() {
        return ApiResponse.ok(importService.listSources());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody SourceRequest request) {
        return ApiResponse.ok(importService.createSource(request.name(), request.type(), request.url(),
                request.publisher(), request.notes(), UserContext.current()));
    }

    @PutMapping("/{id}/enabled")
    public ApiResponse<Void> enabled(@PathVariable long id, @RequestBody EnabledRequest request) {
        importService.setEnabled(id, request.enabled(), UserContext.current());
        return ApiResponse.ok(null);
    }

    @GetMapping("/fixtures")
    public ApiResponse<List<CollectedContent>> fixtures() {
        return ApiResponse.ok(importService.listFixtures());
    }

    @PostMapping("/import/fixture/{fixtureId}")
    public ApiResponse<Map<String, Object>> importFixture(@PathVariable String fixtureId) {
        return ApiResponse.ok(importService.importFixture(fixtureId, UserContext.current()));
    }

    @PostMapping("/import/manual")
    public ApiResponse<Map<String, Object>> importManual(@Valid @RequestBody ManualImportRequest request) {
        CollectionRequest collected = new CollectionRequest(null, request.title(), request.sourceName(), request.sourceType(),
                request.sourceUrl(), request.publisher(), request.publishedAt(), request.body(), request.category());
        return ApiResponse.ok(importService.importManual(request.sourceId(), collected, UserContext.current()));
    }

    @GetMapping("/imports")
    public ApiResponse<List<Map<String, Object>>> imports() {
        return ApiResponse.ok(importService.listImports());
    }

    @GetMapping("/imports/{documentId}")
    public ApiResponse<Map<String, Object>> preview(@PathVariable long documentId) {
        return ApiResponse.ok(importService.preview(documentId));
    }

    @PostMapping("/imports/{documentId}/process")
    public ApiResponse<Map<String, Object>> process(@PathVariable long documentId) {
        return ApiResponse.ok(documentService.process(documentId, UserContext.current()));
    }

    public record SourceRequest(@NotBlank String name, @NotBlank String type, @NotBlank String url,
                                @NotBlank String publisher, String notes) {}
    public record EnabledRequest(boolean enabled) {}
    public record ManualImportRequest(@NotNull Long sourceId, @NotBlank String title, @NotBlank String sourceName,
                                      @NotBlank String sourceType, @NotBlank String sourceUrl, @NotBlank String publisher,
                                      @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime publishedAt,
                                      @NotBlank String body, @NotBlank String category) {}
}
