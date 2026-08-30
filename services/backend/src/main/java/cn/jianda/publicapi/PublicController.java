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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;

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
    public ApiResponse<List<Map<String, Object>>> items(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String regionCode) {
        String sql = "SELECT p.id,p.slug,p.title,p.summary,p.category,p.source_name,p.source_url,p.published_at,"
                + "p.content_kind,p.cover_image_url,p.is_local,p.reading_minutes,p.pinned,p.importance,"
                + "p.publish_channel,p.promote_to_recommend,p.importance_level,"
                + "p.effective_from,p.deadline_at,p.expires_at,p.last_verified_at,p.source_updated_at,p.verification_status,"
                + "p.province,p.city,p.district,p.street_or_town,p.community,p.region_code,p.local_scope,"
                + "d.cover_image_type,d.image_source_name,d.image_source_url,d.image_alt_text,d.image_cached,"
                + "d.image_license_note,"
                + "COALESCE(ev.view_count,0) view_count,COALESCE(el.like_count,0) like_count,"
                + "COALESCE(f.favorite_count,0) favorite_count,COALESCE(r.reminder_count,0) reminder_count,"
                + "(CASE WHEN p.pinned THEN 500 ELSE 0 END + p.importance*5"
                + " + COALESCE(ev.view_count,0)*3 + COALESCE(el.like_count,0)*5"
                + " + COALESCE(f.favorite_count,0)*12 + COALESCE(r.reminder_count,0)*8) hot_score "
                + "FROM published_item p JOIN source_document d ON d.id=p.document_id "
                + "LEFT JOIN (SELECT published_item_id,COUNT(*) view_count FROM content_engagement_event WHERE event_type='VIEW' GROUP BY published_item_id) ev ON ev.published_item_id=p.id "
                + "LEFT JOIN (SELECT published_item_id,COUNT(*) like_count FROM content_engagement_event WHERE event_type='LIKE' GROUP BY published_item_id) el ON el.published_item_id=p.id "
                + "LEFT JOIN (SELECT published_item_id,COUNT(*) favorite_count FROM favorite GROUP BY published_item_id) f ON f.published_item_id=p.id "
                + "LEFT JOIN (SELECT published_item_id,COUNT(*) reminder_count FROM resident_reminder GROUP BY published_item_id) r ON r.published_item_id=p.id "
                + "WHERE p.status='PUBLISHED' AND (p.expires_at IS NULL OR p.expires_at>=CURRENT_TIMESTAMP) ";
        String order = "ORDER BY p.pinned DESC,hot_score DESC,p.importance DESC,p.published_at DESC,p.id DESC";
        List<Object> parameters = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql += "AND p.category=? ";
            parameters.add(category);
        }
        if (regionCode != null && !regionCode.isBlank()) {
            sql += "AND " + PublishedRegionScope.predicate("p") + " ";
            parameters.addAll(PublishedRegionScope.parameters(regionCode));
        }
        return ApiResponse.ok(jdbc.queryForList(sql + order, parameters.toArray()));
    }

    @GetMapping("/regions")
    public ApiResponse<List<Map<String, Object>>> regions() {
        return ApiResponse.ok(SupportedRegions.all().stream().map(region -> Map.<String, Object>of(
                "province", "上海市",
                "city", "上海市",
                "district", region.district(),
                "street_or_town", region.townName(),
                "region_code", region.code()
        )).toList());
    }

    @GetMapping("/search")
    public ApiResponse<List<Map<String, Object>>> search(
            @RequestParam String keyword,
            @RequestParam(required = false) String regionCode) {
        String like = "%" + keyword.trim() + "%";
        String sql = "SELECT p.id,p.slug,p.title,p.summary,p.category,p.source_name,"
                + "p.source_url,p.published_at,p.content_kind,p.cover_image_url,p.is_local,p.reading_minutes,"
                + "p.pinned,p.importance,p.publish_channel,p.promote_to_recommend,p.importance_level,p.effective_from,p.deadline_at,p.expires_at,p.last_verified_at,"
                + "p.source_updated_at,p.verification_status,d.cover_image_type,d.image_source_name,d.image_source_url,"
                + "d.image_alt_text,d.image_cached,d.image_license_note,"
                + "COALESCE(ev.view_count,0) view_count,COALESCE(el.like_count,0) like_count,"
                + "COALESCE(f.favorite_count,0) favorite_count,COALESCE(r.reminder_count,0) reminder_count,"
                + "(CASE WHEN p.pinned THEN 500 ELSE 0 END + p.importance*5"
                + " + COALESCE(ev.view_count,0)*3 + COALESCE(el.like_count,0)*5"
                + " + COALESCE(f.favorite_count,0)*12 + COALESCE(r.reminder_count,0)*8) hot_score "
                + "FROM published_item p JOIN source_document d ON d.id=p.document_id "
                + "LEFT JOIN (SELECT published_item_id,COUNT(*) view_count FROM content_engagement_event WHERE event_type='VIEW' GROUP BY published_item_id) ev ON ev.published_item_id=p.id "
                + "LEFT JOIN (SELECT published_item_id,COUNT(*) like_count FROM content_engagement_event WHERE event_type='LIKE' GROUP BY published_item_id) el ON el.published_item_id=p.id "
                + "LEFT JOIN (SELECT published_item_id,COUNT(*) favorite_count FROM favorite GROUP BY published_item_id) f ON f.published_item_id=p.id "
                + "LEFT JOIN (SELECT published_item_id,COUNT(*) reminder_count FROM resident_reminder GROUP BY published_item_id) r ON r.published_item_id=p.id "
                + "WHERE p.status='PUBLISHED' AND (p.expires_at IS NULL OR p.expires_at>=CURRENT_TIMESTAMP) "
                + "AND (p.title LIKE ? OR p.summary LIKE ? OR p.category LIKE ?) ";
        List<Object> parameters = new ArrayList<>(List.of(like, like, like));
        if (regionCode != null && !regionCode.isBlank()) {
            sql += "AND " + PublishedRegionScope.predicate("p") + " ";
            parameters.addAll(PublishedRegionScope.parameters(regionCode));
        }
        sql += "ORDER BY p.pinned DESC,hot_score DESC,p.importance DESC,p.published_at DESC,p.id DESC";
        return ApiResponse.ok(jdbc.queryForList(sql, parameters.toArray()));
    }

    @GetMapping("/categories")
    public ApiResponse<List<String>> categories() {
        return ApiResponse.ok(List.of("健康", "养老政策", "防诈", "社区服务", "文化学习", "办事通知"));
    }

    @GetMapping("/service-directory")
    public ApiResponse<List<Map<String, Object>>> serviceDirectory(
            @RequestParam(defaultValue = "310113102") String regionCode) {
        return ApiResponse.ok(jdbc.queryForList(
                "SELECT p.id,p.title name,p.category service_type,p.district,p.street_or_town,p.community,"
                        + "MAX(CASE WHEN f.field_type='LOCATION' THEN f.field_value END) address,"
                        + "MAX(CASE WHEN f.field_type='CONTACT' THEN f.field_value END) phone,"
                        + "MAX(CASE WHEN f.field_type IN ('SERVICE_TIME','TIME') THEN f.field_value END) opening_hours,"
                        + "p.summary description,p.source_url,p.source_name,p.last_verified_at "
                        + "FROM published_item p LEFT JOIN extracted_field f ON f.document_id=p.document_id AND f.review_status<>'REJECTED' "
                        + "WHERE p.status='PUBLISHED' AND p.region_code=? "
                        + "AND (p.expires_at IS NULL OR p.expires_at>=CURRENT_TIMESTAMP) "
                        + "AND p.source_url IS NOT NULL AND p.source_url<>'' "
                        + "GROUP BY p.id,p.title,p.category,p.district,p.street_or_town,p.community,p.summary,"
                        + "p.source_url,p.source_name,p.last_verified_at "
                        + "ORDER BY p.last_verified_at DESC,p.published_at DESC", regionCode.trim()));
    }

    @GetMapping("/reminders")
    public ApiResponse<List<Map<String, Object>>> reminders(
            @RequestHeader(value = "X-Anonymous-User", required = false) String user,
            @RequestHeader(value = "X-Resident-Token", required = false) String residentToken) {
        Long residentUserId = resolveResidentUserId(residentToken);
        final String effectiveUser;
        if (residentUserId != null) {
            effectiveUser = "RESIDENT-" + residentUserId;
        } else {
            validateAnonymousUser(user);
            effectiveUser = user;
        }
        return ApiResponse.ok(jdbc.queryForList(
                "SELECT r.id,r.reminder_type,r.remind_at,r.created_at,p.id published_item_id,p.slug,p.title,"
                        + "p.category,p.content_kind,p.status content_status FROM resident_reminder r "
                        + "JOIN published_item p ON p.id=r.published_item_id "
                        + "WHERE r.anonymous_user_id=? ORDER BY r.remind_at,r.id", effectiveUser));
    }

    @PostMapping("/items/{id}/reminder")
    public ApiResponse<Map<String, Object>> createReminder(
            @PathVariable long id,
            @RequestHeader(value = "X-Anonymous-User", required = false) String user,
            @RequestHeader(value = "X-Resident-Token", required = false) String residentToken,
            @RequestBody ReminderRequest request) {
        Long residentUserId = resolveResidentUserId(residentToken);
        final String effectiveUser;
        final Long residentFkId;
        if (residentUserId != null) {
            effectiveUser = "RESIDENT-" + residentUserId;
            residentFkId = residentUserId;
        } else {
            validateAnonymousUser(user);
            effectiveUser = user;
            residentFkId = null;
        }
        Timestamp remindAt = parseReminderTime(request.remindAt());
        String type = request.reminderType() == null ? "CONTENT_TIME" : request.reminderType().trim();
        if (!List.of("CONTENT_TIME", "DEADLINE", "ACTIVITY_START").contains(type)) {
            throw new BusinessException(400, "不支持的提醒类型");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM published_item WHERE id=? AND status='PUBLISHED'",
                Integer.class, id);
        if (count == null || count == 0) throw new BusinessException(404, "内容不存在或已撤回");
        int existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resident_reminder WHERE anonymous_user_id=? AND published_item_id=? AND reminder_type=?",
                Integer.class, effectiveUser, id, type);
        if (existing == 0) {
            if (residentFkId != null) {
                jdbc.update("INSERT INTO resident_reminder(anonymous_user_id,resident_user_id,published_item_id,reminder_type,remind_at) VALUES (?,?,?,?,?)",
                        effectiveUser, residentFkId, id, type, remindAt);
            } else {
                jdbc.update("INSERT INTO resident_reminder(anonymous_user_id,published_item_id,reminder_type,remind_at) VALUES (?,?,?,?)",
                        effectiveUser, id, type, remindAt);
            }
            recordUsage(user != null ? user : effectiveUser, id, "REMINDER_CREATE");
        } else {
            jdbc.update("UPDATE resident_reminder SET remind_at=?,created_at=CURRENT_TIMESTAMP "
                            + "WHERE anonymous_user_id=? AND published_item_id=? AND reminder_type=?",
                    remindAt, effectiveUser, id, type);
        }
        return ApiResponse.ok(Map.of("publishedItemId", id, "reminderType", type, "remindAt", remindAt));
    }

    @DeleteMapping("/reminders/{id}")
    public ApiResponse<Void> deleteReminder(
            @PathVariable long id,
            @RequestHeader(value = "X-Anonymous-User", required = false) String user,
            @RequestHeader(value = "X-Resident-Token", required = false) String residentToken) {
        Long residentUserId = resolveResidentUserId(residentToken);
        final String effectiveUser;
        if (residentUserId != null) {
            effectiveUser = "RESIDENT-" + residentUserId;
        } else {
            validateAnonymousUser(user);
            effectiveUser = user;
        }
        jdbc.update("DELETE FROM resident_reminder WHERE id=? AND anonymous_user_id=?", id, effectiveUser);
        return ApiResponse.ok(null);
    }

    @PostMapping("/items/{id}/event/{eventType}")
    public ApiResponse<Void> usageEvent(
            @PathVariable long id,
            @PathVariable String eventType,
            @RequestHeader(value = "X-Anonymous-User") String user) {
        validateAnonymousUser(user);
        String normalized = eventType.trim().toUpperCase();
        if (!List.of("CONTENT_LISTEN", "SERVICE_PHONE_CLICK", "SERVICE_ADDRESS_COPY").contains(normalized)) {
            throw new BusinessException(400, "不支持的使用事件");
        }
        recordUsage(user, id, normalized);
        return ApiResponse.ok(null);
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
                        + "FROM extracted_field WHERE document_id=? AND review_status<>'REJECTED' ORDER BY id", documentId);
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
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String regionCode) {
        List<Map<String, Object>> currentRows = jdbc.queryForList(
                "SELECT p.id,p.pinned,p.importance,p.published_at,p.category,p.region_code,"
                        + "(CASE WHEN p.pinned THEN 500 ELSE 0 END + p.importance*5 "
                        + "+ COALESCE((SELECT COUNT(*) FROM content_engagement_event WHERE event_type='VIEW' AND published_item_id=p.id),0)*3 "
                        + "+ COALESCE((SELECT COUNT(*) FROM content_engagement_event WHERE event_type='LIKE' AND published_item_id=p.id),0)*5 "
                        + "+ COALESCE((SELECT COUNT(*) FROM favorite WHERE published_item_id=p.id),0)*12 "
                        + "+ COALESCE((SELECT COUNT(*) FROM resident_reminder WHERE published_item_id=p.id),0)*8) hot_score "
                        + "FROM published_item p WHERE slug=? AND p.status='PUBLISHED'", slug);
        if (currentRows.isEmpty()) throw new BusinessException(404, "内容不存在或已撤回");
        Map<String, Object> current = currentRows.get(0);
        String activeCategory = category == null || category.isBlank()
                ? String.valueOf(current.get("category")) : category.trim();
        Object currentRegion = current.get("region_code");
        String activeRegion = regionCode == null || regionCode.isBlank()
                ? currentRegion == null ? "" : String.valueOf(currentRegion) : regionCode;
        Object previous = sameCategory ? adjacent(current, true, activeCategory, activeRegion) : null;
        Object next = sameCategory ? adjacent(current, false, activeCategory, activeRegion) : null;
        if (previous == null) previous = adjacent(current, true, null, activeRegion);
        if (next == null) next = adjacent(current, false, null, activeRegion);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("previous", previous);
        result.put("next", next);
        return ApiResponse.ok(result);
    }

    private Object adjacent(Map<String, Object> current, boolean previous, String category, String regionCode) {
        Object pinnedValue = current.get("pinned");
        int pinned = pinnedValue instanceof Number number ? number.intValue()
                : Boolean.TRUE.equals(pinnedValue) ? 1 : 0;
        long hotScore = current.get("hot_score") == null ? 0L : ((Number) current.get("hot_score")).longValue();
        int importance = ((Number) current.get("importance")).intValue();
        Object publishedAt = current.get("published_at");
        long id = ((Number) current.get("id")).longValue();
        String comparison = previous ? ">" : "<";
        String order = previous ? "ASC" : "DESC";
        String hotExpr = "(CASE WHEN p.pinned THEN 500 ELSE 0 END + p.importance*5 "
                + "+ COALESCE((SELECT COUNT(*) FROM content_engagement_event WHERE event_type='VIEW' AND published_item_id=p.id),0)*3 "
                + "+ COALESCE((SELECT COUNT(*) FROM content_engagement_event WHERE event_type='LIKE' AND published_item_id=p.id),0)*5 "
                + "+ COALESCE((SELECT COUNT(*) FROM favorite WHERE published_item_id=p.id),0)*12 "
                + "+ COALESCE((SELECT COUNT(*) FROM resident_reminder WHERE published_item_id=p.id),0)*8)";
        String sql = "SELECT p.id,p.slug,p.title,p.category,p.cover_image_url,p.content_kind "
                + "FROM published_item p WHERE p.status='PUBLISHED' "
                + (category == null ? "" : "AND p.category=? ")
                + (regionCode == null || regionCode.isBlank() ? "" : "AND " + PublishedRegionScope.predicate("p") + " ")
                + "AND ((CASE WHEN p.pinned THEN 1 ELSE 0 END " + comparison + " ?) "
                + "OR ((CASE WHEN p.pinned THEN 1 ELSE 0 END)=? AND " + hotExpr + " " + comparison + " ?) "
                + "OR ((CASE WHEN p.pinned THEN 1 ELSE 0 END)=? AND " + hotExpr + "=? AND p.importance " + comparison + " ?) "
                + "OR ((CASE WHEN p.pinned THEN 1 ELSE 0 END)=? AND " + hotExpr + "=? AND p.importance=? AND p.published_at " + comparison + " ?) "
                + "OR ((CASE WHEN p.pinned THEN 1 ELSE 0 END)=? AND " + hotExpr + "=? AND p.importance=? AND p.published_at=? AND p.id " + comparison + " ?)) "
                + "ORDER BY p.pinned " + order + "," + hotExpr + " " + order + ",p.importance " + order + ",p.published_at " + order
                + ",p.id " + order + " LIMIT 1";
        List<Object> parameters = new ArrayList<>();
        if (category != null) parameters.add(category);
        if (regionCode != null && !regionCode.isBlank()) parameters.addAll(PublishedRegionScope.parameters(regionCode));
        parameters.addAll(List.of(pinned, pinned, hotScore, pinned, hotScore, importance,
                pinned, hotScore, importance, publishedAt, pinned, hotScore, importance, publishedAt, id));
        return firstOrNull(jdbc.queryForList(sql, parameters.toArray()));
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

    @PostMapping("/items/{id}/view")
    public ApiResponse<Void> view(@PathVariable long id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM published_item WHERE id=? AND status='PUBLISHED'",
                Integer.class, id);
        if (count == null || count == 0) throw new BusinessException(404, "内容不存在或已撤回");
        jdbc.update("INSERT INTO content_engagement_event(published_item_id,event_type) VALUES (?,'VIEW')", id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/items/{slug}/cover")
    public ResponseEntity<byte[]> cover(@PathVariable String slug) throws IOException {
        ResponseEntity<byte[]> response =
                OriginalFileHttp.response(documentService.publicCover(slug), null, false);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.setCacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePublic());
        return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
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

    private static void validateAnonymousUser(String user) {
        if (user == null || user.isBlank() || user.length() > 80) {
            throw new BusinessException(400, "游客标识无效");
        }
    }

    private static Timestamp parseReminderTime(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(400, "请选择提醒时间");
        try {
            Instant instant;
            try {
                instant = Instant.parse(value);
            } catch (DateTimeParseException ignored) {
                instant = OffsetDateTime.parse(value).toInstant();
            }
            return Timestamp.from(instant);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(400, "提醒时间格式不正确");
        }
    }

    private void recordUsage(String user, long contentId, String eventType) {
        jdbc.update("INSERT INTO usage_event(anonymous_session_id,content_id,event_type) VALUES (?,?,?)",
                user, contentId, eventType);
    }

    public record ReminderRequest(String reminderType, String remindAt) {}

    private Long resolveResidentUserId(String token) {
        if (token == null || token.isBlank()) return null;
        String hash = sha256(token);
        List<Long> ids = jdbc.queryForList(
                "SELECT u.id FROM resident_session s JOIN resident_user u ON u.id=s.resident_user_id "
                        + "WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND u.status='ACTIVE'",
                Long.class, hash);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
