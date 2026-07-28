package cn.jianda.publicapi;

import cn.jianda.common.ApiResponse;
import cn.jianda.common.BusinessException;
import cn.jianda.document.DocumentService;
import cn.jianda.document.OriginalFileHttp;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import java.io.IOException;

@RestController
@RequestMapping("/api/public")
public class PublicController {
    private static final Pattern SESSION_PATTERN = Pattern.compile(
            "(?<date>\\d{4}年\\d{1,2}月\\d{1,2}日)\\s*"
                    + "(?<time>\\d{2}:\\d{2}\\s*[-—至]\\s*\\d{2}:\\d{2})\\s*"
                    + "(?<location>[^\\r\\n。；]{2,80}(?:门诊|窗口|服务台|中心|地点))");
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final DocumentService documentService;

    public PublicController(JdbcTemplate jdbc, ObjectMapper objectMapper, DocumentService documentService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.documentService = documentService;
    }

    @GetMapping("/items")
    public ApiResponse<List<Map<String, Object>>> items(@RequestParam(required = false) String category) {
        String sql = "SELECT p.id,p.slug,p.title,p.summary,p.category,p.source_name,p.source_url,p.published_at,"
                + "p.content_kind,p.cover_image_url,p.is_local,p.reading_minutes,p.pinned,p.importance,"
                + "d.cover_image_type,d.image_source_name,d.image_source_url,d.image_alt_text,d.image_cached,"
                + "d.image_license_note "
                + "FROM published_item p JOIN source_document d ON d.id=p.document_id "
                + "WHERE p.status='PUBLISHED' ";
        String order = "ORDER BY p.pinned DESC,p.importance DESC,p.published_at DESC";
        return ApiResponse.ok(category == null || category.isBlank() ? jdbc.queryForList(sql + order)
                : jdbc.queryForList(sql + "AND p.category=? " + order, category));
    }

    @GetMapping("/search")
    public ApiResponse<List<Map<String, Object>>> search(@RequestParam String keyword) {
        String like = "%" + keyword.trim() + "%";
        return ApiResponse.ok(jdbc.queryForList("SELECT p.id,p.slug,p.title,p.summary,p.category,p.source_name,"
                + "p.source_url,p.published_at,p.content_kind,p.cover_image_url,p.is_local,p.reading_minutes,"
                + "p.pinned,p.importance,d.cover_image_type,d.image_source_name,d.image_source_url,"
                + "d.image_alt_text,d.image_cached,d.image_license_note "
                + "FROM published_item p JOIN source_document d ON d.id=p.document_id "
                + "WHERE p.status='PUBLISHED' AND (p.title LIKE ? OR p.summary LIKE ?) "
                + "ORDER BY p.published_at DESC", like, like));
    }

    @GetMapping("/categories")
    public ApiResponse<List<String>> categories() {
        return ApiResponse.ok(List.of("健康", "养老政策", "防诈", "社区服务", "文化学习", "办事通知"));
    }

    @GetMapping("/items/{slug}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT p.*,d.raw_text,d.page_count,d.allow_public_original,d.mime_type,d.storage_path,"
                + "d.source_type,d.original_url,d.canonical_url,d.original_published_at,d.crawl_time,"
                + "d.cover_image_type,d.image_source_name,d.image_source_url,d.image_alt_text,d.image_cached,"
                + "d.image_license_note,d.image_width,d.image_height,d.original_page_available "
                + "FROM published_item p "
                + "JOIN source_document d ON d.id=p.document_id WHERE p.slug=? AND p.status='PUBLISHED'", slug);
        if (rows.isEmpty()) throw new BusinessException(404, "内容不存在或已撤回");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        long documentId = ((Number) result.get("document_id")).longValue();
        Map<String, Object> generated = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT content_type,content_json,plain_text FROM generated_content WHERE document_id=?", documentId)) {
            try {
                generated.put(row.get("content_type").toString(), row.get("content_json") == null ? row.get("plain_text")
                        : objectMapper.readValue(row.get("content_json").toString(), new TypeReference<Object>() {}));
                if ("SUMMARY".equals(row.get("content_type")) && row.get("plain_text") != null) {
                    generated.put("ACCESSIBLE_TEXT", row.get("plain_text"));
                }
            } catch (Exception exception) { generated.put(row.get("content_type").toString(), row.get("plain_text")); }
        }
        result.put("generated", generated);
        List<Map<String, Object>> fields = jdbc.queryForList(
                "SELECT field_type,field_label,field_value,page_no,segment_id,source_quote "
                        + "FROM extracted_field WHERE document_id=? ORDER BY id", documentId);
        result.put("fields", fields);
        result.put("original_file_available", documentService.publicOriginalFileAvailable(result));
        if (!generated.containsKey("SESSIONS")) {
            List<Map<String, Object>> sessions =
                    sessionsFromSource(String.valueOf(result.getOrDefault("raw_text", "")), fields);
            if (!sessions.isEmpty()) generated.put("SESSIONS", sessions);
        }
        return ApiResponse.ok(result);
    }

    @GetMapping("/items/{slug}/neighbors")
    public ApiResponse<Map<String, Object>> neighbors(
            @PathVariable String slug,
            @RequestParam(defaultValue = "false") boolean sameCategory,
            @RequestParam(required = false) String category) {
        List<Map<String, Object>> currentRows = jdbc.queryForList(
                "SELECT id,published_at,category FROM published_item WHERE slug=? AND status='PUBLISHED'", slug);
        if (currentRows.isEmpty()) throw new BusinessException(404, "内容不存在或已撤回");
        Map<String, Object> current = currentRows.get(0);
        long id = ((Number) current.get("id")).longValue();
        Object publishedAt = current.get("published_at");
        String activeCategory = category == null || category.isBlank()
                ? String.valueOf(current.get("category")) : category.trim();
        String compact = "SELECT p.id,p.slug,p.title,p.category,p.cover_image_url,p.content_kind "
                + "FROM published_item p WHERE p.status='PUBLISHED' ";
        String categoryClause = sameCategory ? "AND p.category=? " : "";
        Object previous = firstOrNull(sameCategory
                ? jdbc.queryForList(compact + "AND (p.published_at>? OR (p.published_at=? AND p.id>?)) "
                        + categoryClause + "ORDER BY p.published_at ASC,p.id ASC LIMIT 1",
                        publishedAt, publishedAt, id, activeCategory)
                : jdbc.queryForList(compact + "AND (p.published_at>? OR (p.published_at=? AND p.id>?)) "
                        + "ORDER BY p.published_at ASC,p.id ASC LIMIT 1", publishedAt, publishedAt, id));
        Object next = firstOrNull(sameCategory
                ? jdbc.queryForList(compact + "AND (p.published_at<? OR (p.published_at=? AND p.id<?)) "
                        + categoryClause + "ORDER BY p.published_at DESC,p.id DESC LIMIT 1",
                        publishedAt, publishedAt, id, activeCategory)
                : jdbc.queryForList(compact + "AND (p.published_at<? OR (p.published_at=? AND p.id<?)) "
                        + "ORDER BY p.published_at DESC,p.id DESC LIMIT 1", publishedAt, publishedAt, id));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("previous", previous);
        result.put("next", next);
        return ApiResponse.ok(result);
    }

    private static Object firstOrNull(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }

    @GetMapping("/items/{slug}/original-file")
    public ResponseEntity<byte[]> originalFile(
            @PathVariable String slug,
            @RequestHeader(value = "Range", required = false) String range,
            @RequestParam(defaultValue = "false") boolean download) throws IOException {
        return OriginalFileHttp.response(documentService.publicOriginalFile(slug), range, download);
    }

    @PostMapping("/items/{id}/favorite")
    public ApiResponse<Void> favorite(@PathVariable long id, @RequestHeader(value = "X-Anonymous-User", defaultValue = "demo-user") String user) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM favorite WHERE anonymous_user_id=? AND published_item_id=?", Integer.class, user, id);
        if (count == 0) jdbc.update("INSERT INTO favorite(anonymous_user_id,published_item_id) VALUES (?,?)", user, id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/items/{slug}/cover")
    public ResponseEntity<byte[]> cover(@PathVariable String slug) throws IOException {
        return OriginalFileHttp.response(documentService.publicCover(slug), null, false);
    }

    @DeleteMapping("/items/{id}/favorite")
    public ApiResponse<Void> unfavorite(@PathVariable long id, @RequestHeader(value = "X-Anonymous-User", defaultValue = "demo-user") String user) {
        jdbc.update("DELETE FROM favorite WHERE anonymous_user_id=? AND published_item_id=?", user, id); return ApiResponse.ok(null);
    }

    private static List<Map<String, Object>> sessionsFromSource(
            String source, List<Map<String, Object>> fields) {
        List<Map<String, Object>> sessions = new ArrayList<>();
        Matcher matcher = SESSION_PATTERN.matcher(source);
        long segmentId = fields.stream()
                .map(field -> field.get("segment_id"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToLong(Number::longValue)
                .findFirst().orElse(0);
        while (matcher.find()) {
            sessions.add(Map.of(
                    "date", matcher.group("date").replaceAll("\\s+", ""),
                    "time", matcher.group("time").replaceAll("\\s+", "").replace('—', '-'),
                    "location", matcher.group("location").trim(),
                    "source_quote", matcher.group(),
                    "page_no", 1,
                    "segment_id", segmentId,
                    "needs_human_review", false));
        }
        return sessions;
    }
}
