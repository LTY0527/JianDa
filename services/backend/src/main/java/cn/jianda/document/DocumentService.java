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
import java.util.ArrayList;
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
        String sql = "SELECT d.id,d.title,d.file_name,d.file_type,d.processing_status status,d.page_count,d.created_at,d.updated_at,o.name organization_name,"
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
    public long create(String title, AuthUser user) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO source_document(organization_id,title,processing_status,created_by) VALUES (?,?,'UPLOADED',?)",
                    new String[] {"id"});
            ps.setLong(1, user.organizationId());
            ps.setString(2, title.trim());
            ps.setLong(3, user.id());
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        log(user, "CREATE_DOCUMENT", "SOURCE_DOCUMENT", id, "SUCCESS");
        return id;
    }

    @Transactional
    public Map<String, Object> upload(long id, MultipartFile file, String manualText, AuthUser user) throws IOException {
        assertAccess(id, user);
        if (file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的文件");
        }
        String original = Paths.get(file.getOriginalFilename() == null ? "material" : file.getOriginalFilename())
                .getFileName().toString().replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        String extension = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
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
        jdbc.update("UPDATE source_document SET file_name=?,file_type=?,storage_path=?,raw_text=?,page_count=?,processing_status='UPLOADED',updated_at=CURRENT_TIMESTAMP WHERE id=?",
                original, file.getContentType(), target.toString(), extracted.text(), extracted.pageCount(), id);
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
        jdbc.update("INSERT INTO processing_job(document_id,job_type,status,progress,started_at) VALUES (?,'FULL_PIPELINE','PROCESSING',15,CURRENT_TIMESTAMP)", id);
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
            Map<String, Object> result = aiClient.analyze(document.get("title").toString(), rawText,
                    publicInformation ? "public_news" : "guide",
                    String.valueOf(document.getOrDefault("organization_name", "")),
                    sourceSegments);
            List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
            for (Map<String, Object> field : fields) {
                String quote = String.valueOf(field.get("source_quote"));
                Map<String, Object> source = findSourceSegment(id, quote);
                jdbc.update("INSERT INTO extracted_field(document_id,field_type,field_label,field_value,page_no,segment_id,source_quote,confidence) VALUES (?,?,?,?,?,?,?,?)",
                        id, field.get("field_type"), field.get("label"), field.get("value"), source.get("page_no"),
                        source.get("id"), quote, field.get("confidence"));
            }
            saveGenerated(id, "SUMMARY", "三句话看懂", result.get("summary"), result.get("plain_text"));
            saveGenerated(id, "PLAIN_LANGUAGE", "通俗版", result.get("summary"), result.get("plain_text"));
            saveGenerated(id, "STEP_CARDS", "办理步骤", result.get("steps"), stepsText((List<Map<String, Object>>) result.get("steps")));
            saveGenerated(id, "TERM_EXPLANATION", "专业术语解释", result.get("term_explanations"), "专业术语已生成");
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
            jdbc.update("UPDATE processing_job SET status='SUCCEEDED',progress=100,finished_at=CURRENT_TIMESTAMP WHERE id=?", jobId);
            jdbc.update("UPDATE source_document SET processing_status='WAITING_REVIEW',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            log(user, "PROCESS_DOCUMENT", "SOURCE_DOCUMENT", id, "SUCCESS");
            return Map.of("documentId", id, "status", "WAITING_REVIEW", "progress", 100);
        } catch (RuntimeException exception) {
            LOGGER.error("Document processing failed for document {}", id, exception);
            jdbc.update("UPDATE processing_job SET status='FAILED',error_message=?,finished_at=CURRENT_TIMESTAMP WHERE id=?",
                    truncate(exception.getMessage()), jobId);
            jdbc.update("UPDATE source_document SET processing_status='FAILED',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            log(user, "PROCESS_DOCUMENT", "SOURCE_DOCUMENT", id, "FAILED");
            throw new BusinessException(503, "AI 服务暂时不可用，任务已标记失败，可稍后重试");
        }
    }

    public List<Map<String, Object>> jobs(long id, AuthUser user) {
        assertAccess(id, user);
        return jdbc.queryForList("SELECT * FROM processing_job WHERE document_id=? ORDER BY id DESC", id);
    }

    public List<Map<String, Object>> fields(long id, AuthUser user) {
        assertAccess(id, user);
        return jdbc.queryForList("SELECT * FROM extracted_field WHERE document_id=? ORDER BY id", id);
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
    public Map<String, Object> publish(long id, String title, String category, String sourceName, String sourceUrl, AuthUser user) {
        Map<String, Object> document = detail(id, user);
        int reviews = jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE document_id=? AND action='APPROVE'", Integer.class, id);
        if (reviews == 0) throw new BusinessException(400, "发布前必须完成审核");
        String summary = jdbc.queryForObject("SELECT plain_text FROM generated_content WHERE document_id=? AND content_type='SUMMARY' ORDER BY version DESC LIMIT 1", String.class, id);
        String slug = "guide-" + id;
        jdbc.update("INSERT INTO published_item(document_id,slug,title,summary,category,published_by,source_name,source_url) VALUES (?,?,?,?,?,?,?,?)",
                id, slug, title, summary, category, user.id(), sourceName, sourceUrl);
        jdbc.update("UPDATE source_document SET processing_status='PUBLISHED',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
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

    private void assertAccess(long id, AuthUser user) {
        Integer count = user.isPlatformAdmin()
                ? jdbc.queryForObject("SELECT COUNT(*) FROM source_document WHERE id=?", Integer.class, id)
                : jdbc.queryForObject("SELECT COUNT(*) FROM source_document WHERE id=? AND organization_id=?", Integer.class, id, user.organizationId());
        if (count == null || count == 0) throw new BusinessException(404, "材料不存在或无权访问");
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

    private Map<String, Object> findSourceSegment(long documentId, String quote) {
        List<Map<String, Object>> segments = jdbc.queryForList(
                "SELECT id,page_no,segment_no,text FROM document_segment WHERE document_id=? ORDER BY page_no,segment_no",
                documentId);
        return segments.stream()
                .filter(segment -> String.valueOf(segment.get("text")).contains(quote))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("AI 字段原文依据无法在材料分页正文中定位：" + quote));
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
}

