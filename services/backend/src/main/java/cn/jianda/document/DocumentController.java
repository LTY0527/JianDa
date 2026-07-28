package cn.jianda.document;

import cn.jianda.common.ApiResponse;
import cn.jianda.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService service;
    private final JdbcTemplate jdbc;

    public DocumentController(DocumentService service, JdbcTemplate jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() { return ApiResponse.ok(service.list(UserContext.current())); }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateRequest request) {
        long id = service.create(request, UserContext.current());
        return ApiResponse.ok(Map.of("id", id, "status", "UPLOADED"));
    }

    @PostMapping("/metadata-preview")
    public ApiResponse<Map<String, Object>> metadataPreview(
            @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(service.metadataPreview(file));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) { return ApiResponse.ok(service.detail(id, UserContext.current())); }

    @PostMapping("/{id}/upload")
    public ApiResponse<Map<String, Object>> upload(@PathVariable long id, @RequestPart("file") MultipartFile file,
                                                    @RequestParam(required = false) String manualText) throws IOException {
        return ApiResponse.ok(service.upload(id, file, manualText, UserContext.current()));
    }

    @PostMapping("/{id}/process")
    public ApiResponse<Map<String, Object>> process(@PathVariable long id) { return ApiResponse.ok(service.process(id, UserContext.current())); }

    @PostMapping("/{id}/retry-rewrite")
    public ApiResponse<Map<String, Object>> retryRewrite(@PathVariable long id) {
        return ApiResponse.ok(service.retryRewrite(id, UserContext.current()));
    }

    @GetMapping("/{id}/jobs")
    public ApiResponse<List<Map<String, Object>>> jobs(@PathVariable long id) { return ApiResponse.ok(service.jobs(id, UserContext.current())); }

    @GetMapping("/{id}/segments")
    public ApiResponse<List<Map<String, Object>>> segments(@PathVariable long id) { return ApiResponse.ok(service.segments(id, UserContext.current())); }

    @GetMapping("/{id}/original-file")
    public ResponseEntity<byte[]> originalFile(
            @PathVariable long id,
            @RequestHeader(value = "Range", required = false) String range,
            @RequestParam(defaultValue = "false") boolean download) throws IOException {
        return OriginalFileHttp.response(service.originalFile(id, UserContext.current()), range, download);
    }

    @GetMapping("/{id}/fields")
    public ApiResponse<List<Map<String, Object>>> fields(@PathVariable long id) { return ApiResponse.ok(service.fields(id, UserContext.current())); }

    @PostMapping("/{id}/cover")
    public ApiResponse<Map<String, Object>> uploadCover(
            @PathVariable long id, @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(service.uploadCustomCover(id, file, UserContext.current()));
    }

    @GetMapping("/{id}/generated")
    public ApiResponse<List<Map<String, Object>>> generated(@PathVariable long id) { return ApiResponse.ok(service.generated(id, UserContext.current())); }

    @PutMapping("/{documentId}/fields/{fieldId}")
    public ApiResponse<Void> updateField(@PathVariable long documentId, @PathVariable long fieldId,
                                         @Valid @RequestBody FieldRequest request) {
        service.updateField(documentId, fieldId, request.value(), request.confirmed(), UserContext.current());
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/review")
    public ApiResponse<Void> review(@PathVariable long id, @RequestBody(required = false) ReviewRequest request) {
        service.review(id, request == null ? "审核通过" : request.comment(), UserContext.current());
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/reviews")
    public ApiResponse<List<Map<String, Object>>> reviews(@PathVariable long id) {
        service.detail(id, UserContext.current());
        return ApiResponse.ok(jdbc.queryForList("SELECT * FROM review_record WHERE document_id=? ORDER BY created_at DESC", id));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<Map<String, Object>> publish(@PathVariable long id, @Valid @RequestBody PublishRequest request) {
        return ApiResponse.ok(service.publish(id, request.title(), request.category(), request.sourceName(),
                request.sourceUrl(), request.allowPublicOriginal(), UserContext.current()));
    }

    @PostMapping("/{id}/withdraw")
    public ApiResponse<Void> withdraw(@PathVariable long id) { service.withdraw(id, UserContext.current()); return ApiResponse.ok(null); }

    public record CreateRequest(
            @NotBlank(message = "请输入材料标题") String title,
            String sourceName,
            String documentNumber,
            String sourceType,
            String authorityStatus,
            Double confidence,
            String evidenceQuote,
            String evidenceType,
            Integer pageNo) {}
    public record FieldRequest(@NotBlank(message = "字段内容不能为空") String value, boolean confirmed) {}
    public record ReviewRequest(String comment) {}
    public record PublishRequest(@NotBlank(message = "请输入标题") String title,
                                 @NotBlank(message = "请选择分类") String category,
                                 @NotBlank(message = "请输入来源") String sourceName, String sourceUrl,
                                 boolean allowPublicOriginal) {}
}
