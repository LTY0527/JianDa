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
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class WebArticleController {
    private final WebArticleService service;

    public WebArticleController(WebArticleService service) {
        this.service = service;
    }

    @GetMapping("/sources")
    public ApiResponse<List<Map<String, Object>>> sources() {
        return ApiResponse.ok(service.registries());
    }

    @PostMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@Valid @RequestBody UrlRequest request) {
        return ApiResponse.ok(service.preview(request.url()));
    }

    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importArticle(@Valid @RequestBody UrlRequest request) {
        return ApiResponse.ok(service.importArticle(request.url(), UserContext.current()));
    }

    @PostMapping("/{documentId}/cover/confirm")
    public ApiResponse<Void> confirmCover(@PathVariable long documentId) {
        service.confirmCover(documentId, UserContext.current());
        return ApiResponse.ok(null);
    }

    @PostMapping("/{documentId}/cover/category-default")
    public ApiResponse<Void> categoryDefault(@PathVariable long documentId) {
        service.useCategoryDefault(documentId, UserContext.current());
        return ApiResponse.ok(null);
    }

    @PostMapping("/{documentId}/recrawl")
    public ApiResponse<Map<String, Object>> recrawl(@PathVariable long documentId) {
        return ApiResponse.ok(service.recrawl(documentId, UserContext.current()));
    }

    public record UrlRequest(@NotBlank String url) {}
}
