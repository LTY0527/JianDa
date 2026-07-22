package cn.jianda.publicapi;

import cn.jianda.common.ApiResponse;
import cn.jianda.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class PublicController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PublicController(JdbcTemplate jdbc, ObjectMapper objectMapper) { this.jdbc = jdbc; this.objectMapper = objectMapper; }

    @GetMapping("/items")
    public ApiResponse<List<Map<String, Object>>> items(@RequestParam(required = false) String category) {
        String sql = "SELECT p.id,p.slug,p.title,p.summary,p.category,p.source_name,p.source_url,p.published_at "
                + "FROM published_item p WHERE p.status='PUBLISHED' ";
        return ApiResponse.ok(category == null || category.isBlank() ? jdbc.queryForList(sql + "ORDER BY p.published_at DESC")
                : jdbc.queryForList(sql + "AND p.category=? ORDER BY p.published_at DESC", category));
    }

    @GetMapping("/search")
    public ApiResponse<List<Map<String, Object>>> search(@RequestParam String keyword) {
        String like = "%" + keyword.trim() + "%";
        return ApiResponse.ok(jdbc.queryForList("SELECT id,slug,title,summary,category,source_name,published_at FROM published_item "
                + "WHERE status='PUBLISHED' AND (title LIKE ? OR summary LIKE ?) ORDER BY published_at DESC", like, like));
    }

    @GetMapping("/categories")
    public ApiResponse<List<String>> categories() { return ApiResponse.ok(List.of("时政", "健康", "养老", "反诈", "生活服务", "文化")); }

    @GetMapping("/items/{slug}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT p.*,d.raw_text,d.page_count FROM published_item p "
                + "JOIN source_document d ON d.id=p.document_id WHERE p.slug=? AND p.status='PUBLISHED'", slug);
        if (rows.isEmpty()) throw new BusinessException(404, "内容不存在或已撤回");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        long documentId = ((Number) result.get("document_id")).longValue();
        Map<String, Object> generated = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT content_type,content_json,plain_text FROM generated_content WHERE document_id=?", documentId)) {
            try {
                generated.put(row.get("content_type").toString(), row.get("content_json") == null ? row.get("plain_text")
                        : objectMapper.readValue(row.get("content_json").toString(), new TypeReference<Object>() {}));
            } catch (Exception exception) { generated.put(row.get("content_type").toString(), row.get("plain_text")); }
        }
        result.put("generated", generated);
        result.put("fields", jdbc.queryForList("SELECT field_type,field_label,field_value,page_no,source_quote FROM extracted_field WHERE document_id=? ORDER BY id", documentId));
        return ApiResponse.ok(result);
    }

    @PostMapping("/items/{id}/favorite")
    public ApiResponse<Void> favorite(@PathVariable long id, @RequestHeader(value = "X-Anonymous-User", defaultValue = "demo-user") String user) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM favorite WHERE anonymous_user_id=? AND published_item_id=?", Integer.class, user, id);
        if (count == 0) jdbc.update("INSERT INTO favorite(anonymous_user_id,published_item_id) VALUES (?,?)", user, id);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/items/{id}/favorite")
    public ApiResponse<Void> unfavorite(@PathVariable long id, @RequestHeader(value = "X-Anonymous-User", defaultValue = "demo-user") String user) {
        jdbc.update("DELETE FROM favorite WHERE anonymous_user_id=? AND published_item_id=?", user, id); return ApiResponse.ok(null);
    }
}
