package cn.jianda.document;

import cn.jianda.ai.AiClient;
import cn.jianda.common.BusinessException;
import cn.jianda.security.AuthUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentService.class);
    private static final String EMPTY_AI_FIELDS_MESSAGE =
            "AI未生成可追溯的关键字段，请检查模型输出后重新处理";
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg");
    private final JdbcTemplate jdbc;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final Path uploadRoot;

    public DocumentService(JdbcTemplate jdbc, AiClient aiClient, ObjectMapper objectMapper,
                           @Value("${jianda.upload-dir}") String uploadDir) throws IOException {
        this.jdbc = jdbc;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(uploadRoot);
    }

    public List<Map<String, Object>> list(AuthUser user) {
        String sql = "SELECT d.id,d.title,d.file_name,d.file_type,d.source_type,d.source_name,d.original_published_at,"
                + "d.category,d.content_kind,d.processing_status status,d.page_count,d.created_at,d.updated_at,o.name organization_name,"
                + "COALESCE((SELECT MAX(progress) FROM processing_job j WHERE j.document_id=d.id),0) progress "
                + "FROM source_document d JOIN organization o ON o.id=d.organization_id ";
        if (user.isPlatformAdmin()) {
            return jdbc.queryForList(sql + "ORDER BY d.updated_at DESC");
        }
        return jdbc.queryForList(sql + "WHERE d.organization_id=? ORDER BY d.updated_at DESC", user.organizationId());
    }

    public Map<String, Object> detail(long id, AuthUser user) {
        assertAccess(id, user);
        return jdbc.queryForMap("SELECT d.*,o.name organization_name FROM source_document d JOIN organization o ON o.id=d.organization_id WHERE d.id=?", id);
    }

    @Transactional
    public long create(DocumentController.CreateRequest request, AuthUser user) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO source_document(organization_id,title,source_name,document_number,source_type,"
                            + "authority_status,metadata_confidence,metadata_evidence_quote,metadata_evidence_type,"
                            + "metadata_page_no,processing_status,created_by) VALUES (?,?,?,?,?,?,?,?,?,?,'UPLOADED',?)",
                    new String[] {"id"});
            ps.setLong(1, user.organizationId());
            ps.setString(2, request.title().trim());
            ps.setString(3, trimToNull(request.sourceName()));
            ps.setString(4, trimToNull(request.documentNumber()));
            ps.setString(5, trimToNull(request.sourceType()));
            ps.setString(6, trimToNull(request.authorityStatus()));
            if (request.confidence() == null) ps.setNull(7, java.sql.Types.DECIMAL);
            else ps.setDouble(7, request.confidence());
            ps.setString(8, trimToNull(request.evidenceQuote()));
            ps.setString(9, trimToNull(request.evidenceType()));
            if (request.pageNo() == null) ps.setNull(10, java.sql.Types.INTEGER);
            else ps.setInt(10, request.pageNo());
            ps.setLong(11, user.id());
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        log(user, "CREATE_DOCUMENT", "SOURCE_DOCUMENT", id, "SUCCESS");
        return id;
    }

    public Map<String, Object> metadataPreview(MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new BusinessException(400, "请选择要识别的文件");
        String original = safeOriginalName(file);
        String extension = extension(original);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(400, "仅支持 PDF、PNG、JPG 文件");
        }
        Path previewDir = uploadRoot.resolve(".metadata-preview").normalize();
        Files.createDirectories(previewDir);
        Path temporary = previewDir.resolve(UUID.randomUUID() + "." + extension).normalize();
        if (!temporary.startsWith(previewDir)) throw new BusinessException(400, "文件路径不安全");
        try {
            Files.copy(file.getInputStream(), temporary, StandardCopyOption.REPLACE_EXISTING);
            return aiClient.previewMetadata(temporary, original, file.getContentType());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Transactional
    public Map<String, Object> upload(long id, MultipartFile file, String manualText, AuthUser user) throws IOException {
        assertAccess(id, user);
        if (file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的文件");
        }
        String original = safeOriginalName(file);
        String extension = extension(original);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(400, "仅支持 PDF、PNG、JPG 文件");
        }
        Path orgDir = uploadRoot.resolve(String.valueOf(user.organizationId())).normalize();
        Files.createDirectories(orgDir);
        Path target = orgDir.resolve(UUID.randomUUID() + "." + extension).normalize();
        if (!target.startsWith(uploadRoot)) {
            throw new BusinessException(400, "文件路径不安全");
        }
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        ExtractedDocument extracted = extractDocument(target, original, file.getContentType(), manualText);
        jdbc.update("DELETE FROM document_segment WHERE document_id=?", id);
        for (ExtractedSegment segment : extracted.segments()) {
            jdbc.update("INSERT INTO document_segment(document_id,page_no,segment_no,text,start_offset,end_offset) VALUES (?,?,?,?,?,?)",
                    id, segment.pageNo(), segment.segmentNo(), segment.text(), segment.startOffset(), segment.endOffset());
        }
        String mimeType = file.getContentType() == null ? mimeTypeFor(extension) : file.getContentType();
        jdbc.update("UPDATE source_document SET file_name=?,file_type=?,original_filename=?,mime_type=?,file_size=?,file_sha256=?,"
                        + "storage_path=?,raw_text=?,page_count=?,processing_status='UPLOADED',updated_at=CURRENT_TIMESTAMP WHERE id=?",
                original, mimeType, original, mimeType, Files.size(target), sha256(target),
                target.toString(), extracted.text(), extracted.pageCount(), id);
        log(user, "UPLOAD_DOCUMENT", "SOURCE_DOCUMENT", id, "SUCCESS");
        return detail(id, user);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    @SuppressWarnings("unchecked")
    public Map<String, Object> process(long id, AuthUser user) {
        Map<String, Object> document = detail(id, user);
        String rawText = document.get("raw_text") == null ? "" : document.get("raw_text").toString();
        if (rawText.isBlank()) {
            throw new BusinessException(400, "材料正文为空，请先上传可提取文本的 PDF 或录入正文");
        }
        ensureTraceSegment(id, rawText);
        jdbc.update("DELETE FROM extracted_field WHERE document_id=?", id);
        jdbc.update("DELETE FROM generated_content WHERE document_id=?", id);
        String traceId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO processing_job(document_id,job_type,status,stage,progress,trace_id,started_at) "
                        + "VALUES (?,'FULL_PIPELINE','PROCESSING','EXTRACTING_FACTS',25,?,CURRENT_TIMESTAMP)",
                id, traceId);
        Long jobId = jdbc.queryForObject("SELECT MAX(id) FROM processing_job WHERE document_id=?", Long.class, id);
        jdbc.update("UPDATE source_document SET processing_status='PROCESSING',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        try {
            boolean publicInformation = document.get("content_source_id") != null;
            List<Map<String, Object>> sourceSegments = jdbc.query(
                    "SELECT id,page_no,text FROM document_segment WHERE document_id=? ORDER BY page_no,segment_no",
                    (resultSet, rowNum) -> Map.of(
                            "segment_id", resultSet.getLong("id"),
                            "page_no", resultSet.getInt("page_no"),
                            "text", resultSet.getString("text")),
                    id);
            String sourceName = document.get("source_name") == null
                    ? String.valueOf(document.getOrDefault("organization_name", ""))
                    : String.valueOf(document.get("source_name"));
            jdbc.update("UPDATE processing_job SET stage='EXTRACTING_FACTS',progress=35 WHERE id=?", jobId);
            Map<String, Object> context = new LinkedHashMap<>();
            String fileHash = nullableString(document.get("file_sha256"));
            String contentHash = nullableString(document.get("content_hash"));
            context.put("content_sha256", fileHash.isBlank() ? contentHash : fileHash);
            context.put("document_id", id);
            context.put("processing_job_id", jobId);
            context.put("trace_id", traceId);
            context.put("content_kind", document.get("content_kind"));
            Map<String, Object> result = aiClient.analyze(document.get("title").toString(), rawText,
                    publicInformation ? "public_news" : "guide",
                    sourceName,
                    sourceSegments,
                    context);
            jdbc.update("UPDATE processing_job SET stage='VALIDATING_TRACE',progress=60 WHERE id=?", jobId);
            List<PreparedField> fields = prepareFields(id, result);
            long persistenceStarted = System.nanoTime();
            jdbc.update("UPDATE processing_job SET stage='SAVING_RESULT',progress=85 WHERE id=?", jobId);
            for (PreparedField prepared : fields) {
                Map<String, Object> field = prepared.field();
                SourceTrace source = prepared.source();
                jdbc.update("INSERT INTO extracted_field(document_id,field_type,field_label,field_value,page_no,segment_id,source_quote,confidence) VALUES (?,?,?,?,?,?,?,?)",
                        id, field.get("field_type"), field.get("label"), field.get("value"), source.pageNo(),
                        source.segmentId(), source.quote(), prepared.confidence());
            }
            saveGenerated(id, "SUMMARY", "三句话看懂", result.get("summary"), result.get("plain_text"));
            saveGenerated(id, "PLAIN_LANGUAGE", "通俗版", result.get("summary"), result.get("plain_text"));
            saveGenerated(id, "STEP_CARDS", "办理步骤", result.get("steps"), stepsText((List<Map<String, Object>>) result.get("steps")));
            saveGenerated(id, "TERM_EXPLANATION", "专业术语解释", result.get("term_explanations"), "专业术语已生成");
            if (result.get("sessions") instanceof List<?> sessions && !sessions.isEmpty()) {
                saveGenerated(id, "SESSIONS", "服务场次", sessions, sessions);
            }
            saveStructuredIfPresent(id, result, "audience_rules", "AUDIENCE_RULES", "适用对象与条件");
            saveStructuredIfPresent(id, result, "service_schedule", "SERVICE_SCHEDULE", "分时受理安排");
            saveStructuredIfPresent(id, result, "conditional_materials", "CONDITIONAL_MATERIALS", "分人群材料");
            saveStructuredIfPresent(id, result, "fees", "FEES", "费用与支付方式");
            saveStructuredIfPresent(id, result, "result_delivery", "RESULT_DELIVERY", "领取与邮寄");
            saveStructuredIfPresent(id, result, "deadline_rules", "DEADLINE_RULES", "截止规则");
            saveStructuredIfPresent(id, result, "amendments", "AMENDMENTS", "更正信息");
            if (result.get("warnings") != null) {
                saveGenerated(id, "RISK_WARNING", "风险提示", result.get("warnings"),
                        String.valueOf(result.get("warnings")));
            }
            if (publicInformation) {
                Map<String, Object> source = jdbc.queryForMap("SELECT source_name,source_type,source_url,publisher,published_at "
                        + "FROM content_source WHERE id=?", document.get("content_source_id"));
                saveGenerated(id, "SOURCE_INFO", "来源信息", source, source.get("source_name"));
            }
            saveGenerated(id, "AUDIO_SCRIPT", "语音稿", null, result.get("audio_script"));
            long persistenceMs = Math.max(0, (System.nanoTime() - persistenceStarted) / 1_000_000);
            Map<String, Object> metrics = result.get("metrics") instanceof Map<?, ?> rawMetrics
                    ? (Map<String, Object>) rawMetrics : Map.of();
            long totalMs = metric(metrics, "total_ms") + persistenceMs;
            jdbc.update("UPDATE processing_job SET status='SUCCEEDED',stage='SUCCEEDED',progress=100,"
                            + "schema_version=?,cache_hit=?,text_extract_ms=?,fact_extract_ms=?,trace_validation_ms=?,"
                            + "accessible_rewrite_ms=?,persistence_ms=?,total_ms=?,prompt_tokens=?,completion_tokens=?,"
                            + "total_tokens=?,finished_at=CURRENT_TIMESTAMP WHERE id=?",
                    String.valueOf(metrics.getOrDefault("schema_version", "1.0")),
                    Boolean.TRUE.equals(metrics.get("cache_hit")),
                    metric(metrics, "text_extract_ms"),
                    metric(metrics, "fact_extract_ms"),
                    metric(metrics, "trace_validation_ms"),
                    metric(metrics, "accessible_rewrite_ms"),
                    persistenceMs,
                    totalMs,
                    metric(metrics, "prompt_tokens"),
                    metric(metrics, "completion_tokens"),
                    metric(metrics, "total_tokens"),
                    jobId);
            jdbc.update("UPDATE source_document SET processing_status='WAITING_REVIEW',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            log(user, "PROCESS_DOCUMENT", "SOURCE_DOCUMENT", id, "SUCCESS");
            return Map.of("documentId", id, "status", "WAITING_REVIEW", "stage", "SUCCEEDED",
                    "progress", 100, "cacheHit", Boolean.TRUE.equals(metrics.get("cache_hit")), "totalMs", totalMs);
        } catch (RuntimeException exception) {
            LOGGER.error("Document processing failed for document {}", id, exception);
            String errorMessage = exception instanceof AiResultValidationException
                    ? EMPTY_AI_FIELDS_MESSAGE : truncate(exception.getMessage());
            jdbc.update("UPDATE processing_job SET status='FAILED',stage='FAILED',error_message=?,finished_at=CURRENT_TIMESTAMP WHERE id=?",
                    errorMessage, jobId);
            jdbc.update("UPDATE source_document SET processing_status='FAILED',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            log(user, "PROCESS_DOCUMENT", "SOURCE_DOCUMENT", id, "FAILED");
            throw new BusinessException(503, exception instanceof AiResultValidationException
                    ? EMPTY_AI_FIELDS_MESSAGE : "AI 服务暂时不可用，任务已标记失败，可稍后重试");
        }
    }

    public List<Map<String, Object>> jobs(long id, AuthUser user) {
        assertAccess(id, user);
        return jdbc.queryForList("SELECT * FROM processing_job WHERE document_id=? ORDER BY id DESC", id);
    }

    public List<Map<String, Object>> fields(long id, AuthUser user) {
        assertAccess(id, user);
        List<Map<String, Object>> fields =
                jdbc.queryForList("SELECT * FROM extracted_field WHERE document_id=? ORDER BY id", id);
        markSuspectedDuplicateConditions(fields);
        return fields;
    }

    public List<Map<String, Object>> generated(long id, AuthUser user) {
        assertAccess(id, user);
        return jdbc.queryForList("SELECT * FROM generated_content WHERE document_id=? ORDER BY id", id);
    }

    public List<Map<String, Object>> segments(long id, AuthUser user) {
        assertAccess(id, user);
        List<Map<String, Object>> result = jdbc.queryForList("SELECT * FROM document_segment WHERE document_id=? ORDER BY page_no,segment_no", id);
        if (result.isEmpty()) {
            String text = detail(id, user).get("raw_text").toString();
            return List.of(Map.of("pageNo", 1, "segmentNo", 1, "text", text, "startOffset", 0, "endOffset", text.length()));
        }
        return result;
    }

    public OriginalFile originalFile(long id, AuthUser user) {
        assertAccess(id, user);
        Map<String, Object> source = jdbc.queryForMap(
                "SELECT source_type,storage_path,original_filename,mime_type,file_size,file_sha256 "
                        + "FROM source_document WHERE id=?", id);
        if ("WEB_ARTICLE".equals(source.get("source_type")) || source.get("storage_path") == null) {
            throw new BusinessException(404, "网页文章没有 PDF 或图片原文件，请查看网页正文快照");
        }
        return loadOriginal(source);
    }

    public OriginalFile publicOriginalFile(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.storage_path,d.original_filename,d.mime_type,d.file_size,d.file_sha256 "
                        + "FROM published_item p JOIN source_document d ON d.id=p.document_id "
                        + "WHERE p.slug=? AND p.status='PUBLISHED' AND d.allow_public_original=TRUE",
                slug);
        if (rows.isEmpty()) throw new BusinessException(404, "原文件未公开或内容不存在");
        return loadOriginal(rows.get(0));
    }

    private OriginalFile loadOriginal(Map<String, Object> row) {
        String stored = nullableString(row.get("storage_path"));
        if (stored.isBlank()) throw new BusinessException(404, "该材料没有可预览的原文件");
        Path path = Paths.get(stored).toAbsolutePath().normalize();
        if (!path.startsWith(uploadRoot) || !Files.isRegularFile(path)) {
            throw new BusinessException(404, "原文件不存在");
        }
        try {
            String expectedHash = nullableString(row.get("file_sha256"));
            String actualHash = sha256(path);
            if (!expectedHash.isBlank() && !expectedHash.equalsIgnoreCase(actualHash)) {
                throw new BusinessException(409, "原文件完整性校验失败");
            }
            String name = nullableString(row.get("original_filename"));
            String mime = nullableString(row.get("mime_type"));
            if (name.isBlank()) name = "material";
            if (mime.isBlank()) mime = "application/octet-stream";
            return new OriginalFile(path, name, mime, Files.size(path), actualHash);
        } catch (IOException exception) {
            throw new BusinessException(500, "读取原文件失败");
        }
    }

    @Transactional
    public void updateField(long documentId, long fieldId, String value, boolean confirmed, AuthUser user) {
        assertAccess(documentId, user);
        int count = jdbc.update("UPDATE extracted_field SET field_value=?,review_status=?,reviewer_id=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=? AND document_id=?",
                value.trim(), confirmed ? "CONFIRMED" : "MODIFIED", user.id(), fieldId, documentId);
        if (count == 0) throw new BusinessException(404, "字段不存在");
        log(user, "UPDATE_FIELD", "EXTRACTED_FIELD", fieldId, "SUCCESS");
    }

    @Transactional
    public void review(long id, String comment, AuthUser user) {
        assertAccess(id, user);
        int pending = jdbc.queryForObject("SELECT COUNT(*) FROM extracted_field WHERE document_id=? AND review_status='PENDING'", Integer.class, id);
        if (pending > 0) throw new BusinessException(400, "仍有 " + pending + " 个关键字段未确认");
        jdbc.update("INSERT INTO review_record(document_id,reviewer_id,action,comment) VALUES (?,?,'APPROVE',?)", id, user.id(), comment);
        jdbc.update("UPDATE source_document SET processing_status='REVIEWED',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        log(user, "REVIEW_DOCUMENT", "SOURCE_DOCUMENT", id, "SUCCESS");
    }

    @Transactional
    public Map<String, Object> publish(long id, String title, String category, String sourceName,
                                       String sourceUrl, boolean allowPublicOriginal, AuthUser user) {
        Map<String, Object> document = detail(id, user);
        int reviews = jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE document_id=? AND action='APPROVE'", Integer.class, id);
        if (reviews == 0) throw new BusinessException(400, "发布前必须完成审核");
        boolean webArticle = "WEB_ARTICLE".equals(document.get("source_type"));
        if (webArticle && !Boolean.TRUE.equals(document.get("image_reviewed"))) {
            throw new BusinessException(400, "第三方文章封面尚未人工确认，请确认图片来源或改用分类默认图");
        }
        String summary = jdbc.queryForObject("SELECT plain_text FROM generated_content WHERE document_id=? AND content_type='SUMMARY' ORDER BY version DESC LIMIT 1", String.class, id);
        String slug = (webArticle ? "news-" : "guide-") + id;
        String contentKind = nullableString(document.get("content_kind"));
        String cover = Boolean.TRUE.equals(document.get("image_reviewed"))
                ? nullableString(document.get("cover_image_url")) : "";
        String raw = nullableString(document.get("extracted_text"));
        int readingMinutes = Math.max(1, (int) Math.ceil(raw.length() / 500.0));
        boolean local = nullableString(document.get("source_domain")).endsWith("shanghai.gov.cn");
        int importance = switch (contentKind) {
            case "ANTI_FRAUD", "SERVICE_NOTICE" -> 90;
            case "HEALTH_EDUCATION", "POLICY_NEWS" -> 80;
            case "COMMUNITY_SERVICE" -> 70;
            default -> 50;
        };
        jdbc.update("INSERT INTO published_item(document_id,slug,title,summary,category,published_by,source_name,"
                        + "source_url,content_kind,cover_image_url,is_local,reading_minutes,importance) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, slug, title, summary, category, user.id(), sourceName, sourceUrl,
                contentKind.isBlank() ? null : contentKind, cover.isBlank() ? null : cover,
                local, readingMinutes, importance);
        jdbc.update("UPDATE source_document SET processing_status='PUBLISHED',allow_public_original=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                allowPublicOriginal, id);
        jdbc.update("UPDATE generated_content SET status='PUBLISHED' WHERE document_id=?", id);
        log(user, "PUBLISH_DOCUMENT", "SOURCE_DOCUMENT", id, "SUCCESS");
        return Map.of("id", id, "slug", slug, "title", title, "originalTitle", document.get("title"));
    }

    @Transactional
    public void withdraw(long id, AuthUser user) {
        assertAccess(id, user);
        jdbc.update("UPDATE published_item SET status='WITHDRAWN' WHERE document_id=?", id);
        jdbc.update("UPDATE source_document SET processing_status='WITHDRAWN',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        log(user, "WITHDRAW_DOCUMENT", "SOURCE_DOCUMENT", id, "SUCCESS");
    }

    private void saveGenerated(long id, String type, String title, Object json, Object plainText) {
        try {
            jdbc.update("INSERT INTO generated_content(document_id,content_type,title,content_json,plain_text) VALUES (?,?,?,?,?)",
                    id, type, title, json == null ? null : objectMapper.writeValueAsString(json), String.valueOf(plainText));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("生成内容序列化失败", exception);
        }
    }

    private void saveStructuredIfPresent(long id, Map<String, Object> result,
                                         String key, String type, String title) {
        Object value = result.get(key);
        if (value instanceof List<?> list && list.isEmpty()) return;
        if (value instanceof Map<?, ?> map
                && map.values().stream().allMatch(item -> item instanceof List<?> list && list.isEmpty())) return;
        if (value != null) saveGenerated(id, type, title, value, value);
    }

    private void assertAccess(long id, AuthUser user) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_document WHERE id=?", Integer.class, id);
        if (exists == null || exists == 0) {
            throw new BusinessException(404, "材料记录不存在");
        }
        if (!user.isPlatformAdmin()) {
            Integer accessible = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM source_document WHERE id=? AND organization_id=?",
                    Integer.class, id, user.organizationId());
            if (accessible == null || accessible == 0) {
                throw new BusinessException(403, "当前机构无权访问该材料");
            }
        }
    }

    private void log(AuthUser user, String action, String targetType, long targetId, String result) {
        jdbc.update("INSERT INTO operation_log(operator_id,organization_id,action,target_type,target_id,result,ip) VALUES (?,?,?,?,?,?,'local')",
                user.id(), user.organizationId(), action, targetType, targetId, result);
    }

    private static String stepsText(List<Map<String, Object>> steps) {
        return steps.stream().map(step -> step.get("title") + "：" + step.get("description")).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private static String truncate(String value) {
        if (value == null) return "未知错误";
        return value.length() > 900 ? value.substring(0, 900) : value;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullableString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safeOriginalName(MultipartFile file) {
        return Paths.get(file.getOriginalFilename() == null ? "material" : file.getOriginalFilename())
                .getFileName().toString().replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
    }

    private static String extension(String name) {
        return name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
    }

    private static String mimeTypeFor(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            default -> "application/octet-stream";
        };
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static long metric(Map<String, Object> metrics, String name) {
        Object value = metrics.get(name);
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void markSuspectedDuplicateConditions(List<Map<String, Object>> fields) {
        Map<String, Object> audience = fields.stream()
                .filter(field -> "TARGET_AUDIENCE".equals(field.get("field_type"))).findFirst().orElse(null);
        Map<String, Object> eligibility = fields.stream()
                .filter(field -> "ELIGIBILITY".equals(field.get("field_type"))).findFirst().orElse(null);
        if (audience == null || eligibility == null) return;
        String audienceValue = normalizeComparable(audience.get("field_value"));
        String eligibilityValue = normalizeComparable(eligibility.get("field_value"));
        String audienceQuote = normalizeComparable(audience.get("source_quote"));
        String eligibilityQuote = normalizeComparable(eligibility.get("source_quote"));
        boolean duplicate = audienceValue.equals(eligibilityValue)
                || (audienceQuote.equals(eligibilityQuote)
                && similarity(audienceValue, eligibilityValue) >= 0.88);
        if (duplicate) {
            audience.put("duplicate_suspected", true);
            eligibility.put("duplicate_suspected", true);
        }
    }

    private static String normalizeComparable(Object value) {
        return String.valueOf(value == null ? "" : value)
                .replaceAll("[\\s，,。；;：:]", "");
    }

    private static double similarity(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        int commonPrefix = 0;
        int limit = Math.min(left.length(), right.length());
        while (commonPrefix < limit && left.charAt(commonPrefix) == right.charAt(commonPrefix)) commonPrefix++;
        return (2.0 * commonPrefix) / (left.length() + right.length());
    }

    @SuppressWarnings("unchecked")
    private ExtractedDocument extractDocument(Path target, String fileName, String contentType, String manualText) {
        if (manualText != null && !manualText.isBlank()) {
            String text = manualText.trim();
            return new ExtractedDocument(text, 1, List.of(new ExtractedSegment(1, 1, text, 0, text.length())));
        }
        if (!fileName.toLowerCase().endsWith(".pdf")) {
            return new ExtractedDocument("", 1, List.of());
        }
        Map<String, Object> result = aiClient.extractText(target, fileName, contentType);
        String text = String.valueOf(result.getOrDefault("text", "")).trim();
        if (text.isBlank()) {
            throw new BusinessException(400, "PDF 未提取到可读文本，请检查文件是否为扫描件");
        }
        List<ExtractedSegment> segments = new ArrayList<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) result.getOrDefault("segments", List.of())) {
            segments.add(new ExtractedSegment(
                    number(item.get("page_no")),
                    number(item.get("segment_no")),
                    String.valueOf(item.get("text")),
                    number(item.get("start_offset")),
                    number(item.get("end_offset"))));
        }
        int pageCount = number(result.getOrDefault("page_count", segments.size()));
        return new ExtractedDocument(text, pageCount, segments);
    }

    private List<PreparedField> prepareFields(long documentId, Map<String, Object> result) {
        if (result == null || !(result.get("fields") instanceof List<?> rawFields) || rawFields.isEmpty()) {
            throw new AiResultValidationException();
        }
        List<PreparedField> prepared = new ArrayList<>();
        for (Object item : rawFields) {
            if (!(item instanceof Map<?, ?> rawField)) {
                throw new AiResultValidationException();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> field = (Map<String, Object>) rawField;
            for (String key : List.of("field_type", "label", "value", "source_quote", "confidence")) {
                if (field.get(key) == null || String.valueOf(field.get(key)).isBlank()) {
                    throw new AiResultValidationException();
                }
            }
            double confidence;
            try {
                confidence = field.get("confidence") instanceof Number number
                        ? number.doubleValue() : Double.parseDouble(String.valueOf(field.get("confidence")));
            } catch (NumberFormatException exception) {
                throw new AiResultValidationException();
            }
            if (confidence < 0 || confidence > 1) {
                throw new AiResultValidationException();
            }
            SourceTrace source = findSourceSegment(documentId, String.valueOf(field.get("source_quote")));
            prepared.add(new PreparedField(field, source, confidence));
        }
        if (prepared.isEmpty()) {
            throw new AiResultValidationException();
        }
        return prepared;
    }

    private SourceTrace findSourceSegment(long documentId, String quote) {
        List<Map<String, Object>> segments = jdbc.queryForList(
                "SELECT id,page_no,segment_no,text FROM document_segment WHERE document_id=? ORDER BY page_no,segment_no",
                documentId);
        for (Map<String, Object> segment : segments) {
            String located = locateSourceQuote(String.valueOf(segment.get("text")), quote);
            if (located != null) {
                return new SourceTrace(
                        ((Number) segment.get("id")).longValue(),
                        ((Number) segment.get("page_no")).intValue(),
                        located);
            }
        }
        throw new AiResultValidationException();
    }

    private static String locateSourceQuote(String source, String quote) {
        if (source.contains(quote)) {
            return quote;
        }
        StringBuilder normalizedSource = new StringBuilder();
        List<Integer> sourceIndexes = new ArrayList<>();
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (!Character.isWhitespace(character)) {
                normalizedSource.append(character);
                sourceIndexes.add(index);
            }
        }
        StringBuilder normalizedQuote = new StringBuilder();
        for (int index = 0; index < quote.length(); index++) {
            char character = quote.charAt(index);
            if (!Character.isWhitespace(character)) {
                normalizedQuote.append(character);
            }
        }
        if (normalizedQuote.isEmpty()) {
            return null;
        }
        int start = normalizedSource.indexOf(normalizedQuote.toString());
        if (start < 0) {
            return null;
        }
        int originalStart = sourceIndexes.get(start);
        int originalEnd = sourceIndexes.get(start + normalizedQuote.length() - 1) + 1;
        return source.substring(originalStart, originalEnd);
    }

    private void ensureTraceSegment(long documentId, String rawText) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_segment WHERE document_id=?", Integer.class, documentId);
        if (count != null && count > 0) {
            return;
        }
        jdbc.update(
                "INSERT INTO document_segment(document_id,page_no,segment_no,text,start_offset,end_offset) VALUES (?,1,1,?,0,?)",
                documentId, rawText, rawText.length());
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private record ExtractedDocument(String text, int pageCount, List<ExtractedSegment> segments) {}

    private record ExtractedSegment(int pageNo, int segmentNo, String text, int startOffset, int endOffset) {}

    private record SourceTrace(long segmentId, int pageNo, String quote) {}

    private record PreparedField(Map<String, Object> field, SourceTrace source, double confidence) {}

    public record OriginalFile(Path path, String filename, String mimeType, long size, String sha256) {}

    private static final class AiResultValidationException extends RuntimeException {}
}

