package cn.jianda.document;

import cn.jianda.ai.AiClient;
import cn.jianda.ai.AiQueueService;
import cn.jianda.ai.AiServiceException;
import cn.jianda.common.BusinessException;
import cn.jianda.publicapi.SupportedRegions;
import cn.jianda.security.AuthUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentService.class);
    private static final String EMPTY_AI_FIELDS_MESSAGE =
            "AI未生成可追溯的关键字段，请检查模型输出后重新处理";
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg");
    private static final Set<String> DOCUMENT_KINDS = Set.of(
            "SERVICE_GUIDE", "ACTIVITY_NOTICE", "POLICY_DOCUMENT",
            "STANDARD_SPECIFICATION", "HEALTH_EDUCATION", "ANTI_FRAUD",
            "ELDERLY_SERVICE", "NEWS_ARTICLE", "GENERAL_PUBLIC_SERVICE");
    private static final Set<String> PUBLISH_CHANNELS = Set.of(
            "HEALTH", "ELDERLY", "MEALS", "SERVICES", "FRAUD", "ACTIVITY", "COMMUNITY");
    private static final Pattern CHINESE_DATE = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日");
    private final JdbcTemplate jdbc;
    private final AiClient aiClient;
    private final AiQueueService aiQueueService;
    private final ObjectMapper objectMapper;
    private final Executor documentProcessingExecutor;
    private final Path uploadRoot;

    public DocumentService(JdbcTemplate jdbc, AiClient aiClient, AiQueueService aiQueueService,
                           ObjectMapper objectMapper,
                           @Qualifier("documentProcessingExecutor") Executor documentProcessingExecutor,
                           @Value("${jianda.upload-dir}") String uploadDir) throws IOException {
        this.jdbc = jdbc;
        this.aiClient = aiClient;
        this.aiQueueService = aiQueueService;
        this.objectMapper = objectMapper;
        this.documentProcessingExecutor = documentProcessingExecutor;
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(uploadRoot);
    }

    public List<Map<String, Object>> list(AuthUser user) {
        String sql = "SELECT d.id,d.title,d.file_name,d.file_type,d.source_type,d.source_name,d.original_published_at,"
                + "d.category,d.content_kind,d.publish_channel,d.region_code,"
                + "CONCAT_WS('',d.province,d.city,d.district,d.street_or_town) region_display,"
                + "d.processing_status status,d.page_count,d.created_at,d.updated_at,o.name organization_name,"
                + "COALESCE((SELECT MAX(progress) FROM processing_job j WHERE j.document_id=d.id),0) progress,"
                + "(SELECT j.stage FROM processing_job j WHERE j.document_id=d.id ORDER BY j.id DESC LIMIT 1) stage,"
                + "NULL queue_position "
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
            jdbc.update("INSERT INTO document_segment(document_id,page_no,segment_no,text,raw_text,start_offset,end_offset) VALUES (?,?,?,?,?,?,?)",
                    id, segment.pageNo(), segment.segmentNo(), segment.text(), segment.rawText(),
                    segment.startOffset(), segment.endOffset());
        }
        String mimeType = file.getContentType() == null ? mimeTypeFor(extension) : file.getContentType();
        jdbc.update("UPDATE source_document SET file_name=?,file_type=?,source_type=?,original_filename=?,mime_type=?,file_size=?,file_sha256=?,"
                        + "storage_path=?,raw_text=?,page_count=?,extraction_method=?,ocr_page_count=?,extraction_quality_json=?,"
                        + "processing_status='UPLOADED',updated_at=CURRENT_TIMESTAMP WHERE id=?",
                original, mimeType, "pdf".equals(extension) ? "PDF" : "IMAGE",
                original, mimeType, Files.size(target), sha256(target),
                target.toString(), extracted.text(), extracted.pageCount(), extracted.method(),
                extracted.ocrPageCount(), extracted.qualityJson(), id);
        log(user, "UPLOAD_DOCUMENT", "SOURCE_DOCUMENT", id, "SUCCESS");
        return detail(id, user);
    }

    @Transactional
    public Map<String, Object> process(long id, AuthUser user) {
        Map<String, Object> document = detail(id, user);
        String rawText = document.get("raw_text") == null ? "" : document.get("raw_text").toString();
        if (rawText.isBlank()) {
            throw new BusinessException(400, "材料正文为空，请先上传可提取文本的 PDF 或录入正文");
        }
        jdbc.queryForObject(
                "SELECT id FROM source_document WHERE id=? FOR UPDATE",
                Long.class, id);
        List<Map<String, Object>> running = jdbc.queryForList(
                "SELECT id,status,stage,progress FROM processing_job "
                        + "WHERE document_id=? AND status='PROCESSING' ORDER BY id DESC LIMIT 1",
                id);
        if (!running.isEmpty()) {
            Map<String, Object> current = running.get(0);
            return Map.of(
                    "documentId", id,
                    "jobId", current.get("id"),
                    "status", current.get("status"),
                    "stage", current.get("stage"),
                    "progress", current.get("progress"),
                    "alreadyRunning", true);
        }
        String traceId = UUID.randomUUID().toString();
        Long jobId = insertProcessingJob(id, traceId);
        jdbc.update("UPDATE source_document SET processing_status='PROCESSING',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        dispatchAfterCommit(id, user, jobId);
        return Map.of(
                "documentId", id,
                "jobId", jobId,
                "status", "PROCESSING",
                "stage", "PREPARING",
                "progress", 25);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public Map<String, Object> processQueued(long queueId, AuthUser user) {
        AiQueueService.Reservation reservation = aiQueueService.reserveQueue(queueId);
        if (!reservation.allowed()) return waitingResult(reservation);
        try {
            return processInternal(reservation.documentId(), user, reservation, null);
        } catch (RuntimeException exception) {
            aiQueueService.release(reservation, "PRE_AI_FAILURE");
            throw exception;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> processInternal(long id, AuthUser user,
                                                AiQueueService.Reservation preReserved,
                                                Long existingJobId) {
        Map<String, Object> document = detail(id, user);
        String rawText = document.get("raw_text") == null ? "" : document.get("raw_text").toString();
        if (rawText.isBlank()) {
            throw new BusinessException(400, "材料正文为空，请先上传可提取文本的 PDF 或录入正文");
        }
        ensureTraceSegment(id, rawText);
        String traceId;
        Long jobId;
        if (existingJobId == null) {
            traceId = UUID.randomUUID().toString();
            jobId = insertProcessingJob(id, traceId);
            jdbc.update("UPDATE source_document SET processing_status='PROCESSING',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        } else {
            jobId = existingJobId;
            traceId = jdbc.queryForObject(
                    "SELECT trace_id FROM processing_job WHERE id=? AND document_id=?",
                    String.class, jobId, id);
            jdbc.update("UPDATE processing_job SET status='PROCESSING',stage='EXTRACTING_FACTS',"
                    + "progress=25,error_message=NULL WHERE id=?", jobId);
        }
        AiQueueService.Reservation reservation = preReserved;
        int returnedActualTokens = 0;
        String diagnosticProvider = "";
        String diagnosticModel = "";
        String diagnosticRequestId = "";
        String diagnosticFingerprint = "";
        boolean crossedProviderBoundary = false;
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
            if ("WEB_ARTICLE".equals(document.get("source_type"))) {
                context.put("prompt_version", "web-v1.1");
            }
            reservation = preReserved == null
                    ? aiQueueService.reserveForManual(id, jobId, user) : preReserved;
            if (!reservation.allowed()) {
                jdbc.update("UPDATE processing_job SET status='WAITING_BUDGET',stage='WAITING_BUDGET',progress=25,"
                                + "error_message=?,finished_at=CURRENT_TIMESTAMP WHERE id=?",
                        reservation.reasonSummary(), jobId);
                jdbc.update("UPDATE source_document SET processing_status='UPLOADED',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
                log(user, "PROCESS_DOCUMENT", "SOURCE_DOCUMENT", id, "WAITING_BUDGET");
                return waitingResult(reservation);
            }
            jdbc.update("DELETE FROM extracted_field WHERE document_id=?", id);
            jdbc.update("DELETE FROM generated_content WHERE document_id=?", id);
            Map<String, Object> result;
            try {
                aiQueueService.markExecutionStarted(reservation);
                result = aiClient.analyze(document.get("title").toString(), rawText,
                        publicInformation ? "public_news" : "guide",
                        sourceName,
                        sourceSegments,
                        context);
                crossedProviderBoundary = true;
                Map<String, Object> resultMetrics = result.get("metrics") instanceof Map<?, ?> rawMetrics
                        ? (Map<String, Object>) rawMetrics : Map.of();
                diagnosticProvider = nullableString(resultMetrics.get("provider"));
                diagnosticModel = nullableString(resultMetrics.get("model"));
                diagnosticRequestId = nullableString(resultMetrics.get("request_id"));
                diagnosticFingerprint = nullableString(resultMetrics.get("response_fingerprint"));
                jdbc.update("UPDATE processing_job SET provider_id=?,model_id=?,provider_request_id=?,"
                                + "response_fingerprint=?,crossed_provider_boundary=TRUE WHERE id=?",
                        nullableString(diagnosticProvider), nullableString(diagnosticModel),
                        nullableString(diagnosticRequestId), nullableString(diagnosticFingerprint), jobId);
            } catch (RuntimeException exception) {
                aiQueueService.fail(reservation, tokensFrom(exception),
                        providerFrom(exception), modelFrom(exception), "AI_CALL_FAILED");
                throw exception;
            }
            jdbc.update("UPDATE processing_job SET stage='VALIDATING_TRACE',progress=60 WHERE id=?", jobId);
            if (!hasReviewableContent(result)) {
                throw new AiResultValidationException();
            }
            saveChannelSuggestion(id, result);
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
            saveStructuredIfPresent(id, result, "why_it_matters", "WHY_IT_MATTERS", "与我有什么关系");
            saveStructuredIfPresent(id, result, "action_checklist", "ACTION_CHECKLIST", "行动清单");
            saveStructuredIfPresent(id, result, "key_facts", "KEY_FACTS", "关键事实");
            saveStructuredIfPresent(id, result, "common_mistakes", "COMMON_MISTAKES", "常见误区");
            saveStructuredIfPresent(id, result, "faq", "FAQ", "常见问题");
            saveStructuredIfPresent(id, result, "scope", "CONTENT_SCOPE", "适用范围");
            saveStructuredIfPresent(id, result, "uncertainties", "UNCERTAINTIES", "尚待确认");
            saveStructuredIfPresent(id, result, "document_outline", "DOCUMENT_OUTLINE", "文档目录");
            saveStructuredIfPresent(id, result, "section_summaries", "SECTION_SUMMARIES", "章节摘要");
            saveStructuredIfPresent(id, result, "standard_sections", "STANDARD_SECTIONS", "标准规范结构");
            saveStructuredIfPresent(id, result, "policy_sections", "POLICY_SECTIONS", "政策要点");
            saveStructuredIfPresent(id, result, "health_guidance", "HEALTH_GUIDANCE", "健康指导");
            String documentKind = nullableString(result.get("document_kind"));
            if (!"WEB_ARTICLE".equals(document.get("source_type"))
                    && DOCUMENT_KINDS.contains(documentKind)) {
                jdbc.update("UPDATE source_document SET content_kind=? WHERE id=?", documentKind, id);
            }
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
            saveRewriteStatus(id, result);
            long persistenceMs = Math.max(0, (System.nanoTime() - persistenceStarted) / 1_000_000);
            Map<String, Object> metrics = result.get("metrics") instanceof Map<?, ?> rawMetrics
                    ? (Map<String, Object>) rawMetrics : Map.of();
            returnedActualTokens = (int) metric(metrics, "total_tokens");
            long totalMs = metric(metrics, "total_ms") + persistenceMs;
            aiQueueService.settle(reservation, returnedActualTokens, true,
                    nullableString(metrics.get("provider")), nullableString(metrics.get("model")));
            jdbc.update("UPDATE processing_job SET status='SUCCEEDED',stage='SUCCEEDED',progress=100,reason_code=?,"
                            + "error_message=NULL,last_failed_stage=NULL,"
                            + "schema_version=?,cache_hit=?,text_extract_ms=?,fact_extract_ms=?,trace_validation_ms=?,"
                            + "accessible_rewrite_ms=?,persistence_ms=?,total_ms=?,prompt_tokens=?,completion_tokens=?,"
                            + "total_tokens=?,source_char_count=?,accessible_char_count=?,summary_compression_ratio=?,"
                            + "key_fact_count=?,action_item_count=?,trace_pass_rate=?,hallucinated_field_count=?,"
                            + "markdown_residue_count=?,prompt_version=?,finished_at=CURRENT_TIMESTAMP WHERE id=?",
                    "DETERMINISTIC_FALLBACK".equals(result.get("rewrite_mode"))
                            ? "DETERMINISTIC_FALLBACK" : null,
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
                    metric(metrics, "source_char_count"),
                    metric(metrics, "accessible_char_count"),
                    decimalMetric(metrics, "summary_compression_ratio"),
                    metric(metrics, "key_fact_count"),
                    metric(metrics, "action_item_count"),
                    decimalMetric(metrics, "trace_pass_rate"),
                    metric(metrics, "hallucinated_field_count"),
                    metric(metrics, "markdown_residue_count"),
                    "WEB_ARTICLE".equals(document.get("source_type")) ? "web-v1.1"
                            : String.valueOf(metrics.getOrDefault("schema_version", "1.1")),
                    jobId);
            jdbc.update("UPDATE source_document SET processing_status='WAITING_REVIEW',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            log(user, "PROCESS_DOCUMENT", "SOURCE_DOCUMENT", id, "SUCCESS");
            return Map.of("documentId", id, "status", "WAITING_REVIEW", "stage", "SUCCEEDED",
                    "progress", 100, "cacheHit", Boolean.TRUE.equals(metrics.get("cache_hit")), "totalMs", totalMs);
        } catch (RuntimeException exception) {
            boolean rewriteStageNonCritical = isRewriteStageNonCriticalFailure(exception);
            boolean factsPersisted = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM extracted_field WHERE document_id=?", Integer.class, id) != null
                    && jdbc.queryForObject(
                            "SELECT COUNT(*) FROM extracted_field WHERE document_id=?", Integer.class, id) > 0;
            Map<?, ?> checkpoint = null;
            if (exception instanceof AiServiceException aiFailure
                    && aiFailure.detail().get("fact_checkpoint") instanceof Map<?, ?> cp) {
                checkpoint = cp;
            }
            boolean checkpointHasFacts = checkpoint != null && !checkpoint.isEmpty();

            if (rewriteStageNonCritical && (factsPersisted || checkpointHasFacts)) {
                LOGGER.warn("document_rewrite_schema_recovered document_id={} job_id={} facts_persisted={} checkpoint={}",
                        id, jobId, factsPersisted, checkpoint != null);
                try {
                    // 从失败异常中补齐 provider/model/request_id/fingerprint，
                    // 确保 fallback job 仍记录真实外部调用元数据。
                    if (exception instanceof AiServiceException aiFailure) {
                        diagnosticProvider = defaultString(aiFailure.stringValue("provider"), diagnosticProvider);
                        diagnosticModel = defaultString(aiFailure.stringValue("model"), diagnosticModel);
                        diagnosticRequestId = defaultString(aiFailure.stringValue("request_id"), diagnosticRequestId);
                        diagnosticFingerprint = defaultString(aiFailure.stringValue("response_fingerprint"), diagnosticFingerprint);
                        crossedProviderBoundary = crossedProviderBoundary || !diagnosticRequestId.isBlank();
                    }
                    if (checkpoint != null && !factsPersisted) {
                        persistFactCheckpointFromRaw(id, checkpoint);
                    }
                    Map<String, Object> fallbackResult = buildDeterministicFallbackResult(
                            document, rawText, exception,
                            diagnosticProvider, diagnosticModel, diagnosticRequestId, diagnosticFingerprint,
                            returnedActualTokens, crossedProviderBoundary);
                    long persistenceStarted = System.nanoTime();
                    saveGenerated(id, "SUMMARY", "三句话看懂",
                            fallbackResult.get("summary"), fallbackResult.get("plain_text"));
                    saveGenerated(id, "PLAIN_LANGUAGE", "通俗版",
                            fallbackResult.get("summary"), fallbackResult.get("plain_text"));
                    Object rawSteps = fallbackResult.get("steps");
                    List<Map<String, Object>> steps = rawSteps instanceof List<?> list
                            ? (List<Map<String, Object>>) list : List.of();
                    saveGenerated(id, "STEP_CARDS", "办理步骤", steps, stepsText(steps));
                    saveRewriteStatus(id, fallbackResult);
                    long persistenceMs = Math.max(0, (System.nanoTime() - persistenceStarted) / 1_000_000);
                    Map<String, Object> metrics = fallbackResult.get("metrics") instanceof Map<?, ?> m
                            ? (Map<String, Object>) m : Map.of();
                    int totalTokens = (int) metric(metrics, "total_tokens");
                    long totalMs = metric(metrics, "total_ms") + persistenceMs;
                    aiQueueService.settle(reservation, totalTokens, true,
                            nullableString(metrics.get("provider")), nullableString(metrics.get("model")));
                    // 持久化 fact_checkpoint_json，确保 retry-rewrite 可跳过事实提取阶段直接改写。
                    String retryCheckpointJson = serializeCheckpointForRetry(
                            id, checkpoint, diagnosticModel, diagnosticRequestId, diagnosticFingerprint);
                    jdbc.update("UPDATE processing_job SET status='SUCCEEDED',stage='SUCCEEDED',progress=100,reason_code=?,"
                                    + "error_message=NULL,last_failed_stage=NULL,"
                                    + "schema_version=?,provider_id=?,model_id=?,provider_request_id=?,response_fingerprint=?,"
                                    + "crossed_provider_boundary=?,prompt_tokens=?,completion_tokens=?,total_tokens=?,"
                                    + "persistence_ms=?,total_ms=?,fact_checkpoint_json=?,finished_at=CURRENT_TIMESTAMP WHERE id=?",
                            "REWRITE_ENUM_RECOVERED_VIA_FALLBACK",
                            String.valueOf(metrics.getOrDefault("schema_version", "1.1")),
                            nullableString(diagnosticProvider), nullableString(diagnosticModel),
                            nullableString(diagnosticRequestId), nullableString(diagnosticFingerprint),
                            crossedProviderBoundary, tokenMetric(exception, "prompt_tokens"),
                            tokenMetric(exception, "completion_tokens"),
                            Math.max(totalTokens, tokensFrom(exception)),
                            persistenceMs, totalMs, retryCheckpointJson, jobId);
                    jdbc.update("UPDATE source_document SET processing_status='WAITING_REVIEW',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
                    log(user, "PROCESS_DOCUMENT", "SOURCE_DOCUMENT", id, "REWRITE_FALLBACK_RECOVERED");
                    return Map.of("documentId", id, "status", "WAITING_REVIEW", "stage", "SUCCEEDED",
                            "progress", 100, "rewriteFallback", true, "totalMs", totalMs);
                } catch (RuntimeException fallbackException) {
                    LOGGER.error("Document rewrite fallback also failed for document {}", id, fallbackException);
                }
            }
            if (reservation != null && reservation.allowed()) {
                aiQueueService.fail(reservation, returnedActualTokens, providerFrom(exception), modelFrom(exception),
                        "AI_CALL_OR_PERSISTENCE_FAILED");
            }
            LOGGER.error("Document processing failed for document {}", id, exception);
            String errorMessage = diagnosticMessage(exception);
            String failedStage = exception instanceof AiServiceException aiFailure
                    ? defaultString(aiFailure.stringValue("stage"), "FAILED") : "FAILED";
            String reasonCode = "PROCESSING_FAILED";
            if (exception instanceof AiResultValidationException) {
                failedStage = "fact_validation";
                reasonCode = "NO_TRACEABLE_REVIEW_CONTENT";
            }
            if (exception instanceof AiServiceException aiFailure) {
                diagnosticProvider = defaultString(
                        aiFailure.stringValue("provider"), diagnosticProvider);
                diagnosticModel = defaultString(
                        aiFailure.stringValue("model"), diagnosticModel);
                diagnosticRequestId = defaultString(
                        aiFailure.stringValue("request_id"), diagnosticRequestId);
                diagnosticFingerprint = defaultString(
                        aiFailure.stringValue("response_fingerprint"),
                        diagnosticFingerprint);
                crossedProviderBoundary = crossedProviderBoundary
                        || !diagnosticRequestId.isBlank();
                reasonCode = defaultString(
                        aiFailure.stringValue("reason_code"), "AI_SERVICE_FAILED");
            }
            if (checkpoint != null && exception instanceof AiServiceException aiFailure) {
                persistFactCheckpoint(id, jobId, checkpoint, aiFailure);
            }
            jdbc.update("UPDATE processing_job SET status='FAILED',stage=?,last_failed_stage=?,reason_code=?,"
                            + "provider_id=?,model_id=?,provider_request_id=?,response_fingerprint=?,"
                            + "crossed_provider_boundary=?,prompt_tokens=?,completion_tokens=?,total_tokens=?,"
                            + "error_message=?,finished_at=CURRENT_TIMESTAMP WHERE id=?",
                    failedStage, failedStage, reasonCode,
                    nullableString(diagnosticProvider), nullableString(diagnosticModel),
                    nullableString(diagnosticRequestId), nullableString(diagnosticFingerprint),
                    crossedProviderBoundary, tokenMetric(exception, "prompt_tokens"),
                    tokenMetric(exception, "completion_tokens"), tokensFrom(exception),
                    errorMessage, jobId);
            jdbc.update("UPDATE source_document SET processing_status='FAILED',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            log(user, "PROCESS_DOCUMENT", "SOURCE_DOCUMENT", id, "FAILED");
            throw new BusinessException(503, publicFailureMessage(exception));
        }
    }

    private static boolean isRewriteStageNonCriticalFailure(RuntimeException exception) {
        if (!(exception instanceof AiServiceException aiFailure)) return false;
        String stage = aiFailure.stringValue("stage");
        String errorCode = aiFailure.stringValue("error_code");
        boolean rewriteStage = "accessible_rewrite".equals(stage);
        boolean schemaOrParse = "LLM_SCHEMA_VALIDATION_FAILED".equals(errorCode)
                || "LLM_JSON_PARSE_FAILED".equals(errorCode);
        return rewriteStage && schemaOrParse;
    }

    @SuppressWarnings("unchecked")
    private void persistFactCheckpointFromRaw(long documentId, Map<?, ?> checkpoint) {
        try {
            Object rawFacts = checkpoint.get("facts");
            Map<String, Object> facts = rawFacts instanceof Map<?, ?> map
                    ? objectMapper.convertValue(map, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
                    : Map.of();
            List<PreparedField> fields = prepareFields(documentId, facts);
            jdbc.update("DELETE FROM extracted_field WHERE document_id=?", documentId);
            for (PreparedField prepared : fields) {
                Map<String, Object> field = prepared.field();
                SourceTrace source = prepared.source();
                jdbc.update("INSERT INTO extracted_field(document_id,field_type,field_label,field_value,page_no,segment_id,source_quote,confidence) VALUES (?,?,?,?,?,?,?,?)",
                        documentId, field.get("field_type"), field.get("label"), field.get("value"), source.pageNo(),
                        source.segmentId(), source.quote(), prepared.confidence());
            }
        } catch (IllegalArgumentException checkpointError) {
            LOGGER.error("Failed to persist fact checkpoint (recovery path) for document {}", documentId, checkpointError);
        }
    }

    private Map<String, Object> buildDeterministicFallbackResult(
            Map<String, Object> document, String rawText, RuntimeException exception,
            String provider, String model, String requestId, String fingerprint,
            int alreadyTokens, boolean crossedBoundary) {
        String safeText = rawText == null ? "" : rawText.trim();
        String summary = shorten(safeText, 240);
        if (summary.isBlank()) summary = nullableString(document.get("title"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("plain_text", safeText.isBlank() ? summary : safeText);
        result.put("steps", List.of());
        result.put("term_explanations", List.of());
        result.put("rewrite_mode", "DETERMINISTIC_FALLBACK");
        result.put("normalization_applied", true);
        result.put("normalization_rules", List.of("REWRITE_ENUM_ERROR_FALLBACK", "RAW_TEXT_AS_PLAIN_LANGUAGE"));
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("schema_version", "1.1");
        metrics.put("provider", nullableString(provider).isBlank() ? "fallback" : provider);
        metrics.put("model", nullableString(model).isBlank() ? "recovered" : model);
        metrics.put("request_id", requestId);
        metrics.put("response_fingerprint", fingerprint);
        metrics.put("total_tokens", alreadyTokens);
        metrics.put("prompt_tokens", 0);
        metrics.put("completion_tokens", 0);
        metrics.put("total_ms", 0);
        metrics.put("fact_extract_ms", 0);
        metrics.put("trace_validation_ms", 0);
        metrics.put("accessible_rewrite_ms", 0);
        metrics.put("source_char_count", safeText.length());
        metrics.put("accessible_char_count", safeText.length());
        metrics.put("summary_compression_ratio", summary.isEmpty() ? 0.0 : 1.0 * summary.length() / Math.max(1, safeText.length()));
        metrics.put("key_fact_count", 0);
        metrics.put("action_item_count", 0);
        metrics.put("trace_pass_rate", 1.0);
        metrics.put("hallucinated_field_count", 0);
        metrics.put("markdown_residue_count", 0);
        result.put("metrics", metrics);
        return result;
    }

    private void saveChannelSuggestion(long documentId, Map<String, Object> result) {
        String channel = nullableString(result.get("suggested_publish_channel")).trim().toUpperCase();
        if (!PUBLISH_CHANNELS.contains(channel)) {
            LOGGER.warn("Ignoring invalid publish channel suggestion '{}' for document {}", channel, documentId);
            return;
        }
        double confidence = 0.0;
        Object rawConfidence = result.get("channel_confidence");
        if (rawConfidence instanceof Number number) {
            confidence = number.doubleValue();
        } else if (rawConfidence != null) {
            try {
                confidence = Double.parseDouble(String.valueOf(rawConfidence));
            } catch (NumberFormatException ignored) {
                // Invalid confidence is safely normalized below.
            }
        }
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        String reason = nullableString(result.get("channel_reason")).trim();
        if (reason.isBlank()) reason = "AI 根据当前材料内容给出建议，发布前请人工确认";
        if (reason.length() > 500) reason = reason.substring(0, 500);
        jdbc.update("UPDATE source_document SET suggested_publish_channel=?,channel_confidence=?,channel_reason=? WHERE id=?",
                channel, confidence, reason, documentId);
    }

    private Long insertProcessingJob(long documentId, String traceId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO processing_job(document_id,job_type,status,stage,progress,trace_id,started_at) "
                            + "VALUES (?,'FULL_PIPELINE','PROCESSING','PREPARING',25,?,CURRENT_TIMESTAMP)",
                    new String[] {"id"});
            statement.setLong(1, documentId);
            statement.setString(2, traceId);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("创建处理任务后未返回任务 ID");
        }
        return key.longValue();
    }

    private void dispatchAfterCommit(long documentId, AuthUser user, Long jobId) {
        Runnable dispatch = () -> {
            try {
                documentProcessingExecutor.execute(() -> {
                    try {
                        processInternal(documentId, user, null, jobId);
                    } catch (RuntimeException exception) {
                        LOGGER.warn("Asynchronous document processing ended with failure for document {} job {}",
                                documentId, jobId);
                    }
                });
            } catch (RejectedExecutionException exception) {
                LOGGER.error("Document processing executor rejected document {} job {}", documentId, jobId);
                jdbc.update("UPDATE processing_job SET status='FAILED',stage='QUEUE_REJECTED',"
                                + "last_failed_stage='QUEUE_REJECTED',progress=0,"
                                + "error_message='后台处理队列暂时已满，请稍后重试',finished_at=CURRENT_TIMESTAMP WHERE id=?",
                        jobId);
                jdbc.update("UPDATE source_document SET processing_status='FAILED',updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        documentId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
    }

    private void persistFactCheckpoint(long documentId, Long jobId, Map<?, ?> checkpoint,
                                       AiServiceException failure) {
        try {
            Object rawFacts = checkpoint.get("facts");
            Map<String, Object> facts = rawFacts instanceof Map<?, ?> map
                    ? objectMapper.convertValue(map, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
                    : Map.of();
            List<PreparedField> fields = prepareFields(documentId, facts);
            jdbc.update("DELETE FROM extracted_field WHERE document_id=?", documentId);
            for (PreparedField prepared : fields) {
                Map<String, Object> field = prepared.field();
                SourceTrace source = prepared.source();
                jdbc.update("INSERT INTO extracted_field(document_id,field_type,field_label,field_value,page_no,segment_id,source_quote,confidence) VALUES (?,?,?,?,?,?,?,?)",
                        documentId, field.get("field_type"), field.get("label"), field.get("value"), source.pageNo(),
                        source.segmentId(), source.quote(), prepared.confidence());
            }
            String checkpointJson = objectMapper.writeValueAsString(checkpoint);
            jdbc.update("UPDATE processing_job SET fact_checkpoint_json=?,fact_response_fingerprint=?,provider_request_id=?,"
                            + "schema_version=?,prompt_version=?,fact_extract_ms=?,prompt_tokens=?,completion_tokens=?,total_tokens=? WHERE id=?",
                    checkpointJson,
                    checkpoint.get("response_fingerprint"),
                    checkpoint.get("request_id"),
                    checkpoint.get("schema_version"),
                    checkpoint.get("prompt_version"),
                    longValue(checkpoint.get("fact_extract_ms")),
                    intValue(checkpoint.get("prompt_tokens")),
                    intValue(checkpoint.get("completion_tokens")),
                    intValue(checkpoint.get("total_tokens")),
                    jobId);
        } catch (JsonProcessingException | IllegalArgumentException checkpointError) {
            LOGGER.error("Failed to persist fact checkpoint for document {}", documentId, checkpointError);
        }
    }

    /**
     * 为 fallback 后的 retry-rewrite 准备可序列化的事实检查点 JSON。
     * 优先使用异常携带的 checkpoint；缺失时从已持久化的 extracted_field 重建最小检查点，
     * 以便 retry-rewrite 仍可跳过事实提取阶段。返回 null 表示无可用检查点。
     */
    private String serializeCheckpointForRetry(long documentId, Map<?, ?> checkpoint,
                                               String diagnosticModel, String diagnosticRequestId,
                                               String diagnosticFingerprint) {
        Map<?, ?> source = (checkpoint != null && !checkpoint.isEmpty()) ? checkpoint : null;
        if (source == null) {
            List<Map<String, Object>> rawFields = jdbc.queryForList(
                    "SELECT field_type,field_label,field_value,page_no,segment_id,source_quote,confidence "
                            + "FROM extracted_field WHERE document_id=? ORDER BY id", documentId);
            if (rawFields.isEmpty()) return null;
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Map<String, Object> raw : rawFields) {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("field_type", raw.get("field_type"));
                field.put("label", raw.get("field_label"));
                field.put("value", raw.get("field_value"));
                field.put("page_no", raw.get("page_no"));
                field.put("segment_id", raw.get("segment_id"));
                field.put("source_quote", raw.get("source_quote"));
                field.put("confidence", raw.get("confidence"));
                fields.add(field);
            }
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("prompt_version", "v1");
            facts.put("fields", fields);
            facts.put("sessions", List.of());
            Map<String, Object> rebuilt = new LinkedHashMap<>();
            rebuilt.put("prompt_version", "v1");
            rebuilt.put("schema_version", "1.1");
            rebuilt.put("model", nullableString(diagnosticModel).isBlank() ? "recovered" : diagnosticModel);
            rebuilt.put("response_fingerprint", nullableString(diagnosticFingerprint));
            rebuilt.put("request_id", nullableString(diagnosticRequestId));
            rebuilt.put("fact_extract_ms", 0);
            rebuilt.put("prompt_tokens", 0);
            rebuilt.put("completion_tokens", 0);
            rebuilt.put("total_tokens", 0);
            rebuilt.put("facts", facts);
            source = rebuilt;
        }
        try {
            return objectMapper.writeValueAsString(source);
        } catch (JsonProcessingException serializationError) {
            LOGGER.warn("Failed to serialize fact checkpoint for retry document {}", documentId, serializationError);
            return null;
        }
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String diagnosticMessage(RuntimeException exception) {
        if (exception instanceof AiResultValidationException) return EMPTY_AI_FIELDS_MESSAGE;
        if (exception instanceof AiServiceException aiFailure) {
            return truncate(String.join(" | ", List.of(
                    defaultString(aiFailure.stringValue("message"), "AI 服务暂时不可用"),
                    "stage=" + defaultString(aiFailure.stringValue("stage"), "unknown"),
                    "json_path=" + defaultString(aiFailure.stringValue("json_path"), "unknown"),
                    "request_id=" + defaultString(aiFailure.stringValue("request_id"), "unknown"))));
        }
        return "AI 服务暂时不可用";
    }

    private String publicFailureMessage(RuntimeException exception) {
        if (exception instanceof AiResultValidationException) return EMPTY_AI_FIELDS_MESSAGE;
        if (!(exception instanceof AiServiceException aiFailure)) {
            return "AI 服务暂时不可用，任务已标记失败，可稍后重试";
        }
        if (!"LLM_SCHEMA_VALIDATION_FAILED".equals(aiFailure.stringValue("error_code"))
                && !"LLM_JSON_PARSE_FAILED".equals(aiFailure.stringValue("error_code"))) {
            return "AI 服务暂时不可用，任务已标记失败，可稍后重试";
        }
        String stage = "accessible_rewrite".equals(aiFailure.stringValue("stage"))
                ? "适老化改写" : "事实提取";
        String path = aiFailure.stringValue("json_path").replace("$.", "");
        String requestId = aiFailure.stringValue("request_id");
        StringBuilder message = new StringBuilder("外部模型返回内容格式不完整；错误阶段：").append(stage);
        if (!path.isBlank() && !"$".equals(path)) message.append("；字段：").append(path);
        if ("accessible_rewrite".equals(aiFailure.stringValue("stage"))) {
            message.append("；本次事实提取结果已保留，可以仅重试改写阶段");
        }
        if (!requestId.isBlank()) message.append("；请求编号：").append(requestId);
        return message.toString();
    }

    private static Map<String, Object> waitingResult(AiQueueService.Reservation reservation) {
        Map<String, Object> waiting = new LinkedHashMap<>();
        waiting.put("documentId", reservation.documentId());
        waiting.put("status", "WAITING_BUDGET");
        waiting.put("stage", "WAITING_BUDGET");
        waiting.put("reasonCode", reservation.reasonCode());
        waiting.put("reason", reservation.reasonSummary());
        waiting.put("estimatedRecoveryAt", reservation.estimatedRecoveryAt());
        waiting.put("actualTokens", 0);
        return waiting;
    }

    private static int tokensFrom(RuntimeException exception) {
        if (!(exception instanceof AiServiceException failure)) return 0;
        Object value = failure.detail().get("actual_tokens");
        if (!(value instanceof Number)) value = failure.detail().get("total_tokens");
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private static String providerFrom(RuntimeException exception) {
        return exception instanceof AiServiceException failure ? failure.stringValue("provider") : "";
    }

    private static String modelFrom(RuntimeException exception) {
        return exception instanceof AiServiceException failure ? failure.stringValue("model") : "";
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    @SuppressWarnings("unchecked")
    public Map<String, Object> retryRewrite(long id, AuthUser user) {
        Map<String, Object> document = detail(id, user);
        String status = String.valueOf(document.get("processing_status"));
        if ("PUBLISHED".equals(status)) throw new BusinessException(409, "已发布材料不能重新生成");
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processing_job WHERE document_id=? AND status='PROCESSING'",
                Integer.class, id);
        if (active != null && active > 0) throw new BusinessException(409, "材料正在处理中，请勿重复提交");
        List<Map<String, Object>> checkpoints = jdbc.queryForList(
                "SELECT * FROM processing_job WHERE document_id=? AND fact_checkpoint_json IS NOT NULL ORDER BY id DESC LIMIT 1",
                id);
        if (checkpoints.isEmpty()) throw new BusinessException(409, "尚无可用的事实提取检查点，请先完整处理材料");
        Map<String, Object> previous = checkpoints.get(0);
        String checkpointJson = String.valueOf(previous.get("fact_checkpoint_json"));
        Map<String, Object> checkpoint;
        try {
            checkpoint = objectMapper.readValue(checkpointJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException exception) {
            throw new BusinessException(409, "事实提取检查点不可用，请重新完整处理材料");
        }
        String traceId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO processing_job(document_id,job_type,status,stage,progress,trace_id,retry_count,started_at) "
                        + "VALUES (?,'REWRITE_ONLY','PROCESSING','REWRITE_PENDING',60,?,?,CURRENT_TIMESTAMP)",
                id, traceId, intValue(previous.get("retry_count")) + 1);
        Long jobId = jdbc.queryForObject("SELECT MAX(id) FROM processing_job WHERE document_id=?", Long.class, id);
        jdbc.update("UPDATE source_document SET processing_status='PROCESSING',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        AiQueueService.Reservation reservation = aiQueueService.reserveForManual(id, jobId, user);
        if (!reservation.allowed()) {
            jdbc.update("UPDATE processing_job SET status='WAITING_BUDGET',stage='WAITING_BUDGET',progress=60,"
                            + "error_message=?,finished_at=CURRENT_TIMESTAMP WHERE id=?",
                    reservation.reasonSummary(), jobId);
            jdbc.update("UPDATE source_document SET processing_status='FAILED',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            return waitingResult(reservation);
        }
        int returnedActualTokens = 0;
        String diagnosticProvider = "";
        String diagnosticModel = "";
        String diagnosticRequestId = "";
        String diagnosticFingerprint = "";
        boolean crossedProviderBoundary = false;
        try {
            List<Map<String, Object>> sourceSegments = jdbc.query(
                    "SELECT id,page_no,text FROM document_segment WHERE document_id=? ORDER BY page_no,segment_no",
                    (resultSet, rowNum) -> Map.of("segment_id", resultSet.getLong("id"),
                            "page_no", resultSet.getInt("page_no"), "text", resultSet.getString("text")), id);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("document_id", id);
            context.put("processing_job_id", jobId);
            context.put("trace_id", traceId);
            context.put("content_kind", document.get("content_kind"));
            context.put("prompt_version", "WEB_ARTICLE".equals(document.get("source_type")) ? "web-v1.1" : previous.get("prompt_version"));
            Map<String, Object> result;
            try {
                aiQueueService.markExecutionStarted(reservation);
                result = aiClient.rewrite(String.valueOf(document.get("title")),
                        String.valueOf(document.get("raw_text")),
                        document.get("content_source_id") != null ? "public_news" : "guide",
                        String.valueOf(document.getOrDefault("source_name", "")), sourceSegments, context, checkpoint);
                crossedProviderBoundary = true;
            } catch (RuntimeException exception) {
                aiQueueService.fail(reservation, tokensFrom(exception),
                        providerFrom(exception), modelFrom(exception), "AI_CALL_FAILED");
                throw exception;
            }
            jdbc.update("DELETE FROM generated_content WHERE document_id=?", id);
            saveRewriteResult(id, result, document.get("content_source_id") != null ? document : null);
            Map<String, Object> metrics = result.get("metrics") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            diagnosticProvider = nullableString(metrics.get("provider"));
            diagnosticModel = nullableString(metrics.get("model"));
            diagnosticRequestId = nullableString(metrics.get("request_id"));
            diagnosticFingerprint = nullableString(metrics.get("response_fingerprint"));
            returnedActualTokens = (int) metric(metrics, "total_tokens");
            aiQueueService.settle(reservation, returnedActualTokens, true,
                    nullableString(metrics.get("provider")), nullableString(metrics.get("model")));
            jdbc.update("UPDATE processing_job SET status='SUCCEEDED',stage='SUCCEEDED',progress=100,reason_code=?,"
                            + "error_message=NULL,last_failed_stage=NULL,accessible_rewrite_ms=?,"
                            + "prompt_tokens=?,completion_tokens=?,total_tokens=?,provider_id=?,model_id=?,provider_request_id=?,"
                            + "response_fingerprint=?,crossed_provider_boundary=?,finished_at=CURRENT_TIMESTAMP WHERE id=?",
                    "DETERMINISTIC_FALLBACK".equals(result.get("rewrite_mode"))
                            ? "DETERMINISTIC_FALLBACK" : null,
                    metric(metrics, "accessible_rewrite_ms"), metric(metrics, "prompt_tokens"),
                    metric(metrics, "completion_tokens"), metric(metrics, "total_tokens"),
                    diagnosticProvider, diagnosticModel, diagnosticRequestId, diagnosticFingerprint,
                    crossedProviderBoundary, jobId);
            jdbc.update("UPDATE source_document SET processing_status='WAITING_REVIEW',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            return Map.of("documentId", id, "status", "WAITING_REVIEW", "stage", "SUCCEEDED", "progress", 100);
        } catch (RuntimeException exception) {
            aiQueueService.fail(reservation, returnedActualTokens, providerFrom(exception), modelFrom(exception),
                    "AI_CALL_OR_PERSISTENCE_FAILED");
            String failedStage = exception instanceof AiServiceException aiFailure
                    ? defaultString(aiFailure.stringValue("stage"), "accessible_rewrite") : "accessible_rewrite";
            String reasonCode = "PROCESSING_FAILED";
            if (exception instanceof AiServiceException aiFailure) {
                diagnosticProvider = defaultString(aiFailure.stringValue("provider"), diagnosticProvider);
                diagnosticModel = defaultString(aiFailure.stringValue("model"), diagnosticModel);
                diagnosticRequestId = defaultString(aiFailure.stringValue("request_id"), diagnosticRequestId);
                diagnosticFingerprint = defaultString(aiFailure.stringValue("response_fingerprint"), diagnosticFingerprint);
                returnedActualTokens = Math.max(returnedActualTokens, tokensFrom(exception));
                crossedProviderBoundary = crossedProviderBoundary || !diagnosticRequestId.isBlank();
                reasonCode = defaultString(aiFailure.stringValue("reason_code"), "AI_SERVICE_FAILED");
            }
            jdbc.update("UPDATE processing_job SET status='FAILED',stage=?,last_failed_stage=?,reason_code=?,"
                            + "provider_id=?,model_id=?,provider_request_id=?,response_fingerprint=?,crossed_provider_boundary=?,"
                            + "prompt_tokens=?,completion_tokens=?,total_tokens=?,error_message=?,finished_at=CURRENT_TIMESTAMP WHERE id=?",
                    failedStage, failedStage, reasonCode, diagnosticProvider, diagnosticModel,
                    diagnosticRequestId, diagnosticFingerprint, crossedProviderBoundary,
                    tokenMetric(exception, "prompt_tokens"), tokenMetric(exception, "completion_tokens"),
                    returnedActualTokens, diagnosticMessage(exception), jobId);
            jdbc.update("UPDATE source_document SET processing_status='FAILED',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            throw new BusinessException(503, publicFailureMessage(exception));
        }
    }

    private static int tokenMetric(RuntimeException exception, String key) {
        if (!(exception instanceof AiServiceException failure)) return 0;
        Object value = failure.detail().get(key);
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private void saveRewriteResult(long id, Map<String, Object> result, Map<String, Object> publicDocument) {
        saveGenerated(id, "SUMMARY", "三句话看懂", result.get("summary"), result.get("plain_text"));
        saveGenerated(id, "PLAIN_LANGUAGE", "通俗版", result.get("summary"), result.get("plain_text"));
        Object rawSteps = result.get("steps");
        List<Map<String, Object>> steps = rawSteps instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        saveGenerated(id, "STEP_CARDS", "办理步骤", steps, stepsText(steps));
        saveGenerated(id, "TERM_EXPLANATION", "专业术语解释", result.get("term_explanations"), "专业术语已生成");
        for (String[] item : List.of(
                new String[]{"why_it_matters", "WHY_IT_MATTERS", "与我有什么关系"},
                new String[]{"action_checklist", "ACTION_CHECKLIST", "行动清单"},
                new String[]{"key_facts", "KEY_FACTS", "关键事实"},
                new String[]{"common_mistakes", "COMMON_MISTAKES", "常见误区"},
                new String[]{"faq", "FAQ", "常见问题"},
                new String[]{"uncertainties", "UNCERTAINTIES", "尚待确认"})) {
            saveStructuredIfPresent(id, result, item[0], item[1], item[2]);
        }
        if (result.get("warnings") != null) saveGenerated(id, "RISK_WARNING", "风险提示", result.get("warnings"), result.get("warnings"));
        saveGenerated(id, "AUDIO_SCRIPT", "语音稿", null, result.get("audio_script"));
        saveRewriteStatus(id, result);
    }

    private void saveRewriteStatus(long id, Map<String, Object> result) {
        if (!"DETERMINISTIC_FALLBACK".equals(result.get("rewrite_mode"))) return;
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("rewrite_mode", "DETERMINISTIC_FALLBACK");
        status.put("normalization_applied", result.getOrDefault("normalization_applied", false));
        status.put("normalization_rules", result.getOrDefault("normalization_rules", List.of()));
        saveGenerated(id, "REWRITE_STATUS", "生成状态", status,
                "已生成基础易读版本；AI自然化表达暂未成功，可稍后重新优化。");
    }

    public List<Map<String, Object>> jobs(long id, AuthUser user) {
        assertAccess(id, user);
        return jdbc.queryForList("SELECT * FROM processing_job WHERE document_id=? ORDER BY id DESC", id);
    }

    @Transactional
    public Map<String, Object> updateRegionScope(
            long id, DocumentController.RegionScopeRequest request, AuthUser user) {
        detail(id, user);
        String scope = request.localScope().trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("LOCAL_TOWN", "DISTRICT_SHARED", "CITY_SHARED", "NATIONAL_SHARED", "UNCLASSIFIED")
                .contains(scope)) throw new BusinessException(400, "地域范围不正确");
        String province = nullableString(request.province());
        String city = nullableString(request.city());
        String district = nullableString(request.district());
        String town = nullableString(request.streetOrTown());
        String code = nullableString(request.regionCode());
        if ("LOCAL_TOWN".equals(scope)) {
            SupportedRegions.Region region = SupportedRegions.require(code);
            if (!"上海市".equals(province) || !"上海市".equals(city)
                    || !region.district().equals(district) || !region.townName().equals(town)) {
                throw new BusinessException(400, "本地镇内容必须归属已开通的宝山区镇");
            }
        } else if ("DISTRICT_SHARED".equals(scope)) {
            if (!"宝山区".equals(district)) throw new BusinessException(400, "区级共享内容必须明确归属宝山区");
            town = "";
            code = "310113";
        } else if ("CITY_SHARED".equals(scope)) {
            if (!"上海市".equals(city)) throw new BusinessException(400, "市级共享内容必须明确归属上海市");
            district = "";
            town = "";
            code = "310000";
        } else if ("NATIONAL_SHARED".equals(scope)) {
            province = "全国";
            city = "";
            district = "";
            town = "";
            code = "100000";
        } else {
            province = city = district = town = code = "";
        }
        jdbc.update("UPDATE source_document SET province=?,city=?,district=?,street_or_town=?,region_code=?,"
                        + "local_scope=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                emptyToNull(province), emptyToNull(city), emptyToNull(district), emptyToNull(town), emptyToNull(code), scope, id);
        jdbc.update("UPDATE published_item SET province=?,city=?,district=?,street_or_town=?,region_code=?,local_scope=? "
                        + "WHERE document_id=?",
                emptyToNull(province), emptyToNull(city), emptyToNull(district), emptyToNull(town), emptyToNull(code), scope, id);
        log(user, "UPDATE_REGION_SCOPE", "SOURCE_DOCUMENT", id, "SUCCESS");
        return detail(id, user);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Lightweight polling projection. It intentionally excludes source text, fields and generated content. */
    public Map<String, Object> processingSnapshot(long id, AuthUser user) {
        assertAccess(id, user);
        Map<String, Object> document = jdbc.queryForMap(
                "SELECT processing_status,updated_at FROM source_document WHERE id=?", id);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,status,stage,progress,error_message,started_at,finished_at,updated_at,total_ms,"
                        + "GREATEST(0,TIMESTAMPDIFF(SECOND,started_at,COALESCE(finished_at,CURRENT_TIMESTAMP))) AS elapsed_seconds,"
                        + "CASE WHEN updated_at < TIMESTAMPADD(SECOND,-600,CURRENT_TIMESTAMP) "
                        + "THEN 1 ELSE 0 END AS heartbeat_stale,"
                        + "provider_id,model_id,reason_code,retry_count FROM processing_job "
                        + "WHERE document_id=? ORDER BY id DESC LIMIT 1", id);
        Map<String, Object> job = rows.isEmpty() ? Map.of() : rows.get(0);
        if ("PROCESSING".equals(job.get("status"))
                && job.get("heartbeat_stale") instanceof Number staleFlag
                && staleFlag.intValue() == 1) {
            Object jobId = job.get("id");
            jdbc.update("UPDATE processing_job SET status='FAILED_RETRYABLE',stage='HEARTBEAT_STALE',"
                            + "last_failed_stage=COALESCE(stage,'UNKNOWN'),error_message='任务心跳超时，可安全重试',"
                            + "finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PROCESSING'",
                    jobId);
            jdbc.update("UPDATE source_document SET processing_status='FAILED',updated_at=CURRENT_TIMESTAMP "
                    + "WHERE id=? AND processing_status='PROCESSING'", id);
            rows = jdbc.queryForList(
                    "SELECT id,status,stage,progress,error_message,started_at,finished_at,updated_at,total_ms,"
                            + "GREATEST(0,TIMESTAMPDIFF(SECOND,started_at,COALESCE(finished_at,CURRENT_TIMESTAMP))) AS elapsed_seconds,"
                            + "CASE WHEN updated_at < TIMESTAMPADD(SECOND,-600,CURRENT_TIMESTAMP) "
                            + "THEN 1 ELSE 0 END AS heartbeat_stale,"
                            + "provider_id,model_id,reason_code,retry_count FROM processing_job WHERE id=?", jobId);
            job = rows.get(0);
            document.put("processing_status", "FAILED");
        }
        int reviewRows = jdbc.queryForObject(
                "SELECT (SELECT COUNT(*) FROM extracted_field WHERE document_id=? AND review_status<>'REJECTED') "
                        + "+ (SELECT COUNT(*) FROM generated_content WHERE document_id=?)",
                Integer.class, id, id);
        Map<String, Object> result = new LinkedHashMap<>();
        int queuePosition = 0;
        int activeCount = 0;
        String estimatedMs = null;
        Object docJobId = job.get("id");
        if (docJobId != null) {
            try {
                String queueSql = "SELECT "
                        + "(SELECT COUNT(*) FROM processing_job q "
                        + " WHERE q.id<? AND q.status IN ('PROCESSING','WAITING_BUDGET','WAITING_APPROVAL')) AS pos,"
                        + "(SELECT COUNT(*) FROM processing_job q WHERE q.status='PROCESSING') AS active_count,"
                        + "AVG(CASE WHEN j.finished_at IS NOT NULL AND j.started_at IS NOT NULL THEN TIMESTAMPDIFF(MICROSECOND,j.started_at,j.finished_at)/1000 ELSE NULL END) AS avg_ms "
                        + "FROM processing_job j WHERE j.status IN ('SUCCEEDED','PROCESSING','WAITING_BUDGET','WAITING_APPROVAL') "
                        + "AND j.job_type='FULL_PIPELINE' AND j.started_at > TIMESTAMPADD(HOUR,-72,CURRENT_TIMESTAMP)";
                List<Map<String, Object>> stats = jdbc.queryForList(queueSql, docJobId);
                if (!stats.isEmpty()) {
                    Map<String, Object> stat = stats.get(0);
                    queuePosition = toInt(stat.get("pos"));
                    activeCount = toInt(stat.get("active_count"));
                    Object avgMs = stat.get("avg_ms");
                    if (avgMs instanceof Number) {
                        Number n = (Number) avgMs;
                        if (n.doubleValue() > 0) {
                            double prog = toDouble(job.get("progress")) / 100.0;
                            long remainingMs = Math.max(0L, Math.round(n.doubleValue() * (1.0 - prog)));
                            if (queuePosition > 0) remainingMs += Math.round(n.doubleValue()) * queuePosition;
                            estimatedMs = String.valueOf(remainingMs);
                        } else {
                            estimatedMs = null;
                        }
                    } else {
                        estimatedMs = null;
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        result.put("documentId", id);
        result.put("status", document.get("processing_status"));
        result.put("stage", job.getOrDefault("stage", "PREPARING"));
        result.put("progress", job.getOrDefault("progress", 0));
        result.put("elapsed", toInt(job.get("elapsed_seconds")));
        result.put("estimatedMs", estimatedMs);
        result.put("queuePosition", queuePosition);
        result.put("activeProcessing", activeCount);
        result.put("heartbeat", job.get("updated_at"));
        result.put("jobId", job.get("id"));
        result.put("jobStatus", job.get("status"));
        result.put("error", job.get("error_message"));
        result.put("hasReviewContent", reviewRows > 0);
        result.put("updatedAt", document.get("updated_at"));
        result.put("version", job.get("updated_at"));
        result.put("totalMs", job.get("total_ms"));
        result.put("providerId", job.get("provider_id"));
        result.put("modelId", job.get("model_id"));
        result.put("reasonCode", job.get("reason_code"));
        result.put("retryCount", job.getOrDefault("retry_count", 0));
        return result;
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) return Math.max(0, n.intValue());
        return 0;
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    public List<Map<String, Object>> fields(long id, AuthUser user) {
        assertAccess(id, user);
        List<Map<String, Object>> fields =
                jdbc.queryForList("SELECT * FROM extracted_field WHERE document_id=? AND review_status<>'REJECTED' ORDER BY id", id);
        markSuspectedDuplicateConditions(fields);
        return fields;
    }

    public List<Map<String, Object>> generated(long id, AuthUser user) {
        assertAccess(id, user);
        return jdbc.queryForList("SELECT * FROM generated_content WHERE document_id=? ORDER BY id", id);
    }

    @Transactional
    public void updateGenerated(long id, String contentType, String plainText, Object contentJson, AuthUser user) {
        assertAccess(id, user);
        String type = contentType == null ? "" : contentType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("SUMMARY", "PLAIN_LANGUAGE", "FAQ", "UNCERTAINTIES", "AUDIO_SCRIPT").contains(type)) {
            throw new BusinessException(400, "该生成内容不支持人工修改");
        }
        String serialized = null;
        if (contentJson != null) {
            try { serialized = objectMapper.writeValueAsString(contentJson); }
            catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new BusinessException(400, "结构化内容格式不正确");
            }
        }
        int changed = jdbc.update("UPDATE generated_content SET plain_text=COALESCE(?,plain_text),content_json=COALESCE(?,content_json),"
                        + "status=CASE WHEN status='PUBLISHED' THEN 'PUBLISHED' ELSE 'REVIEWED' END WHERE document_id=? AND content_type=?",
                plainText == null ? null : plainText.trim(), serialized, id, type);
        if (changed == 0) throw new BusinessException(404, "生成内容不存在");
        if ("SUMMARY".equals(type) && plainText != null) {
            jdbc.update("UPDATE published_item SET summary=? WHERE document_id=?", plainText.trim(), id);
        }
        log(user, "UPDATE_GENERATED_CONTENT", "SOURCE_DOCUMENT", id, "SUCCESS");
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
                "SELECT source_type,storage_path,file_name,original_filename,mime_type,file_size,file_sha256 "
                        + "FROM source_document WHERE id=?", id);
        if ("WEB_ARTICLE".equals(source.get("source_type")) || source.get("storage_path") == null) {
            throw new BusinessException(404, "网页文章没有 PDF 或图片原文件，请查看网页正文快照");
        }
        return loadOriginal(source);
    }

    public OriginalFile publicOriginalFile(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.source_type,d.storage_path,d.file_name,d.original_filename,d.mime_type,d.file_size,d.file_sha256 "
                        + "FROM published_item p JOIN source_document d ON d.id=p.document_id "
                        + "WHERE p.slug=? AND p.status='PUBLISHED' AND d.allow_public_original=TRUE "
                        + "AND d.source_type IN ('PDF','IMAGE')",
                slug);
        if (rows.isEmpty()) throw new BusinessException(404, "原文件未公开或内容不存在");
        return loadOriginal(rows.get(0));
    }

    @Transactional
    public Map<String, Object> uploadCustomCover(
            long id, MultipartFile file, AuthUser user) throws IOException {
        assertAccess(id, user);
        Map<String, Object> document = detail(id, user);
        if (!"WEB_ARTICLE".equals(document.get("source_type"))) {
            throw new BusinessException(400, "只有网页文章可以设置编辑封面");
        }
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) {
            throw new BusinessException(400, "请选择不超过 5MB 的 JPG、PNG 或 WebP 图片");
        }
        String original = safeOriginalName(file);
        String ext = extension(original);
        if (!Set.of("jpg", "jpeg", "png", "webp").contains(ext)) {
            throw new BusinessException(400, "封面仅支持 JPG、PNG 或 WebP");
        }
        Path coverDir = uploadRoot.resolve("covers").resolve(String.valueOf(user.organizationId())).normalize();
        Files.createDirectories(coverDir);
        Path target = coverDir.resolve(UUID.randomUUID() + "." + ext).normalize();
        if (!target.startsWith(uploadRoot)) throw new BusinessException(400, "封面路径不安全");
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        String mime = file.getContentType() == null ? mimeTypeFor(ext) : file.getContentType();
        jdbc.update("UPDATE source_document SET custom_cover_path=?,custom_cover_mime=?,custom_cover_filename=?,"
                        + "cover_image_type='EDITOR_UPLOAD',image_source_name='机构编辑上传',"
                        + "image_source_url=NULL,image_cached=TRUE,image_reviewed=TRUE WHERE id=?",
                target.toString(), mime, original, id);
        return Map.of("filename", original, "mimeType", mime, "imageReviewed", true);
    }

    public OriginalFile publicCover(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.custom_cover_path storage_path,d.custom_cover_filename original_filename,"
                        + "d.custom_cover_mime mime_type,NULL file_sha256 "
                        + "FROM published_item p JOIN source_document d ON d.id=p.document_id "
                        + "WHERE p.slug=? AND p.status='PUBLISHED' AND d.custom_cover_path IS NOT NULL",
                slug);
        if (rows.isEmpty()) throw new BusinessException(404, "自定义封面不存在");
        return loadOriginal(rows.get(0));
    }

    public boolean publicOriginalFileAvailable(Map<String, Object> document) {
        if (!Boolean.TRUE.equals(document.get("allow_public_original"))) return false;
        String sourceType = nullableString(document.get("source_type"));
        if (!Set.of("PDF", "IMAGE").contains(sourceType)) return false;
        String stored = nullableString(document.get("storage_path"));
        if (stored.isBlank()) return false;
        Path path = Paths.get(stored).toAbsolutePath().normalize();
        return path.startsWith(uploadRoot) && Files.isRegularFile(path);
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
            if (name.isBlank()) name = nullableString(row.get("file_name"));
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
        String previous = jdbc.queryForObject(
                "SELECT field_value FROM extracted_field WHERE id=? AND document_id=?",
                String.class, fieldId, documentId);
        int count = jdbc.update("UPDATE extracted_field SET field_value=?,review_status=?,reviewer_id=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=? AND document_id=?",
                value.trim(), confirmed ? "CONFIRMED" : "MODIFIED", user.id(), fieldId, documentId);
        if (count == 0) throw new BusinessException(404, "字段不存在");
        if (previous != null && !previous.equals(value.trim())) {
            jdbc.update("UPDATE processing_job SET human_modified_field_count=human_modified_field_count+1,"
                            + "human_modified_char_count=human_modified_char_count+? "
                            + "WHERE id=(SELECT job_id FROM (SELECT MAX(id) job_id FROM processing_job WHERE document_id=?) latest)",
                    changedCharacters(previous, value.trim()), documentId);
        }
        log(user, "UPDATE_FIELD", "EXTRACTED_FIELD", fieldId, "SUCCESS");
    }

    @Transactional
    public void rejectField(long documentId, long fieldId, AuthUser user) {
        assertAccess(documentId, user);
        int count = jdbc.update(
                "UPDATE extracted_field SET review_status='REJECTED',reviewer_id=?,reviewed_at=CURRENT_TIMESTAMP "
                        + "WHERE id=? AND document_id=? AND review_status<>'REJECTED'",
                user.id(), fieldId, documentId);
        if (count == 0) throw new BusinessException(404, "字段不存在或已排除");
        log(user, "REJECT_FIELD", "EXTRACTED_FIELD", fieldId, "SUCCESS");
    }

    private static int changedCharacters(String before, String after) {
        int shared = 0;
        int limit = Math.min(before.length(), after.length());
        while (shared < limit && before.charAt(shared) == after.charAt(shared)) shared++;
        return (before.length() - shared) + (after.length() - shared);
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

    public static String compactPublicationSummary(Object contentJson, String plainText) {
        String source = "";
        if (contentJson instanceof List<?> list) {
            source = list.stream().map(String::valueOf)
                    .filter(value -> !value.isBlank()).reduce((left, right) -> left + " " + right).orElse("");
        } else if (contentJson instanceof Map<?, ?> map) {
            source = map.values().stream().map(String::valueOf)
                    .filter(value -> !value.isBlank()).reduce((left, right) -> left + " " + right).orElse("");
        } else if (contentJson != null) {
            source = String.valueOf(contentJson);
        }
        if (source.isBlank()) source = plainText == null ? "" : plainText;
        String cleaned = source
                .replaceAll("(?s)```(?:\\w+)?\\s*(.*?)```", " $1 ")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("<[^>]+>", " ")
                .replace("**", "").replace("__", "")
                .replaceAll("[*_~`>|]", " ")
                .replaceAll("\\s+", " ").trim();
        int maxLength = 120;
        if (cleaned.length() <= maxLength) return cleaned;
        return cleaned.substring(0, maxLength).replaceAll("[，、；：\\s]+$", "") + "…";
    }

    private String publicationSummary(long documentId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT content_json,plain_text FROM generated_content WHERE document_id=? "
                        + "AND content_type='SUMMARY' ORDER BY version DESC LIMIT 1",
                documentId);
        Object contentJson = null;
        String serialized = nullableString(row.get("content_json"));
        if (!serialized.isBlank()) {
            try {
                contentJson = objectMapper.readValue(serialized, Object.class);
            } catch (JsonProcessingException ignored) {
                contentJson = serialized;
            }
        }
        return compactPublicationSummary(contentJson, nullableString(row.get("plain_text")));
    }

    private String validatedPublicationScope(Map<String, Object> document) {
        String rawScope = nullableString(document.get("local_scope")).trim().toUpperCase(java.util.Locale.ROOT);
        String regionCode = nullableString(document.get("region_code"));
        String province = nullableString(document.get("province"));
        String city = nullableString(document.get("city"));
        String district = nullableString(document.get("district"));
        String town = nullableString(document.get("street_or_town"));
        String scope = switch (rawScope) {
            case "LOCAL_TOWN", "LOCAL", "TOWN", "STREET" -> "LOCAL_TOWN";
            case "DISTRICT_SHARED", "DISTRICT" -> "DISTRICT_SHARED";
            case "CITY_SHARED", "CITY" -> "CITY_SHARED";
            case "NATIONAL_SHARED", "NATIONAL" -> "NATIONAL_SHARED";
            case "PROVINCE" -> {
                if ("100000".equals(regionCode)) yield "NATIONAL_SHARED";
                throw new BusinessException(400, "省级内容尚未配置可公开的居民地区范围");
            }
            default -> throw new BusinessException(400, "发布前必须明确内容适用地区，未分类内容不能直接公开");
        };
        if ("LOCAL_TOWN".equals(scope)) {
            SupportedRegions.Region region = SupportedRegions.require(regionCode);
            if (!"上海市".equals(province) || !"上海市".equals(city)
                    || !region.district().equals(district) || !region.townName().equals(town)) {
                throw new BusinessException(400, "本地镇内容的地区信息不完整，请重新选择发布地区");
            }
        } else if ("DISTRICT_SHARED".equals(scope)) {
            if (!"宝山区".equals(district) || !town.isBlank()) {
                throw new BusinessException(400, "区级共享内容必须明确归属宝山区且不能绑定具体街镇");
            }
        } else if ("CITY_SHARED".equals(scope)) {
            if (!"上海市".equals(city) || !district.isBlank() || !town.isBlank()) {
                throw new BusinessException(400, "市级共享内容必须明确归属上海市且不能绑定区或街镇");
            }
        } else if (!"全国".equals(province) || !city.isBlank() || !district.isBlank() || !town.isBlank()) {
            throw new BusinessException(400, "全国共享内容必须使用全国范围且不能绑定地方行政区");
        }
        return scope;
    }

    @Transactional
    public Map<String, Object> publish(long id, String title, String category, String sourceName,
                                       String sourceUrl, boolean allowPublicOriginal, String publishChannel,
                                       boolean promoteToRecommend, String importanceLevel, AuthUser user) {
        Map<String, Object> document = detail(id, user);
        int reviews = jdbc.queryForObject("SELECT COUNT(*) FROM review_record WHERE document_id=? AND action='APPROVE'", Integer.class, id);
        if (reviews == 0) throw new BusinessException(400, "发布前必须完成审核");
        boolean webArticle = "WEB_ARTICLE".equals(document.get("source_type"));
        if (webArticle && !Boolean.TRUE.equals(document.get("image_reviewed"))) {
            throw new BusinessException(400, "第三方文章封面尚未人工确认，请确认图片来源或改用分类默认图");
        }
        String publicationScope = validatedPublicationScope(document);
        String summary = publicationSummary(id);
        String slug = (webArticle ? "news-" : "guide-") + id;
        String contentKind = nullableString(document.get("content_kind"));
        String cover = Boolean.TRUE.equals(document.get("image_reviewed"))
                ? nullableString(document.get("cover_image_url")) : "";
        if (!nullableString(document.get("custom_cover_path")).isBlank()) {
            cover = "/api/public/items/" + slug + "/cover";
        }
        String raw = nullableString(document.get("extracted_text"));
        int readingMinutes = Math.max(1, (int) Math.ceil(raw.length() / 500.0));
        boolean local = !nullableString(document.get("region_code")).isBlank()
                || nullableString(document.get("source_domain")).endsWith("shanghai.gov.cn");
        String channel = publishChannel == null ? "" : publishChannel.trim().toUpperCase();
        if (channel.isBlank()) {
            channel = suggestedChannel(category, contentKind, title);
        }
        if (!List.of("HEALTH", "ELDERLY", "MEALS", "SERVICES", "FRAUD", "ACTIVITY", "COMMUNITY").contains(channel)) {
            throw new BusinessException(400, "请选择有效的发布栏目");
        }
        String level = importanceLevel == null ? "NORMAL" : importanceLevel.trim().toUpperCase();
        if (!List.of("NORMAL", "IMPORTANT", "URGENT").contains(level)) {
            throw new BusinessException(400, "请选择有效的重要程度");
        }
        int importance = switch (level) {
            case "URGENT" -> 100;
            case "IMPORTANT" -> 85;
            default -> 60;
        };
        Timestamp effectiveFrom = lifecycleDate(id, "START_DATE", false);
        Timestamp deadlineAt = lifecycleDate(id, "END_DATE", true);
        Timestamp expiresAt = deadlineAt == null ? null
                : Timestamp.valueOf(deadlineAt.toLocalDateTime().toLocalDate().plusDays(1).atStartOfDay());
        jdbc.update("INSERT INTO published_item(document_id,slug,title,summary,category,published_by,source_name,"
                        + "source_url,content_kind,cover_image_url,is_local,reading_minutes,importance,province,city,"
                        + "district,street_or_town,community,region_code,local_scope,effective_from,deadline_at,expires_at,"
                        + "last_verified_at,verification_status,publish_channel,promote_to_recommend,importance_level,pinned) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, slug, title, summary, category, user.id(), sourceName, sourceUrl,
                contentKind.isBlank() ? null : contentKind, cover.isBlank() ? null : cover,
                local, readingMinutes, importance, document.get("province"), document.get("city"),
                document.get("district"), document.get("street_or_town"), document.get("community"),
                document.get("region_code"), publicationScope,
                effectiveFrom, deadlineAt, expiresAt, Timestamp.valueOf(LocalDateTime.now()), "VERIFIED",
                channel, promoteToRecommend, level, promoteToRecommend);
        if (document.get("previous_version_id") instanceof Number previous) {
            jdbc.update("UPDATE published_item SET status='WITHDRAWN' WHERE document_id=?", previous.longValue());
        }
        jdbc.update("UPDATE source_document SET processing_status='PUBLISHED',allow_public_original=?,publish_channel=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                allowPublicOriginal, channel, id);
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

    @Transactional
    public void updatePublicationChannel(long id, String publishChannel, AuthUser user) {
        assertAccess(id, user);
        String channel = nullableString(publishChannel).trim().toUpperCase(java.util.Locale.ROOT);
        if (!PUBLISH_CHANNELS.contains(channel)) {
            throw new BusinessException(400, "请选择有效的发布栏目");
        }
        Integer published = jdbc.queryForObject(
                "SELECT COUNT(*) FROM published_item WHERE document_id=? AND status='PUBLISHED'",
                Integer.class, id);
        if (published == null || published == 0) {
            throw new BusinessException(400, "仅已发布内容可以调整栏目");
        }
        jdbc.update("UPDATE published_item SET publish_channel=? WHERE document_id=? AND status='PUBLISHED'",
                channel, id);
        jdbc.update("UPDATE source_document SET publish_channel=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                channel, id);
        log(user, "UPDATE_PUBLICATION_CHANNEL", "SOURCE_DOCUMENT", id, "SUCCESS");
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
        if (value != null) {
            try {
                saveGenerated(id, type, title, value, value);
            } catch (RuntimeException ignore) {
                LOGGER.warn("structured_content_skipped document_id={} type={} reason={}",
                        id, type, ignore.getClass().getSimpleName());
            }
        }
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

    private static String suggestedChannel(String category, String contentKind, String title) {
        String text = String.join(" ", nullableString(category), nullableString(contentKind), nullableString(title));
        if (text.matches(".*(健康|卫生|医疗|体检|疫苗|HEALTH).*")) return "HEALTH";
        if (text.matches(".*(养老|助老|长者|老年|银龄|ELDERLY).*")) return "ELDERLY";
        if (text.matches(".*(助餐|食堂|用餐|餐饮|MEALS).*")) return "MEALS";
        if (text.matches(".*(反诈|诈骗|FRAUD).*")) return "FRAUD";
        if (text.matches(".*(活动|报名|讲座|ACTIVITY).*")) return "ACTIVITY";
        if (text.matches(".*(办事|办理|材料|SERVICE_NOTICE|SERVICES).*")) return "SERVICES";
        return "COMMUNITY";
    }

    private static String shorten(String value, int maxLength) {
        if (value == null) return "";
        String trimmed = value.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= maxLength) return trimmed;
        int cut = Math.max(0, Math.min(maxLength - 1, trimmed.lastIndexOf(' ', maxLength - 1)));
        if (cut < maxLength / 2) cut = maxLength - 1;
        return trimmed.substring(0, cut).trim() + "…";
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Timestamp lifecycleDate(long documentId, String fieldType, boolean endOfDay) {
        List<String> values = jdbc.queryForList(
                "SELECT field_value FROM extracted_field WHERE document_id=? AND field_type=? "
                        + "AND field_value IS NOT NULL AND field_value<>'' ORDER BY id LIMIT 1",
                String.class, documentId, fieldType);
        if (values.isEmpty()) return null;
        LocalDate date = parseDate(values.get(0));
        if (date == null) return null;
        return Timestamp.valueOf(endOfDay ? date.atTime(23, 59, 59) : date.atStartOfDay());
    }

    private static LocalDate parseDate(String value) {
        Matcher chinese = CHINESE_DATE.matcher(value == null ? "" : value);
        if (chinese.find()) {
            return LocalDate.of(Integer.parseInt(chinese.group(1)),
                    Integer.parseInt(chinese.group(2)), Integer.parseInt(chinese.group(3)));
        }
        try {
            Matcher iso = Pattern.compile("(\\d{4}-\\d{1,2}-\\d{1,2})").matcher(value == null ? "" : value);
            if (iso.find()) return LocalDate.parse(iso.group(1), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            // 无法可靠识别的日期不写入生命周期字段，继续交由人工审核。
        }
        return null;
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

    private static double decimalMetric(Map<String, Object> metrics, String name) {
        Object value = metrics.get(name);
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? 0 : Double.parseDouble(String.valueOf(value));
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
            return new ExtractedDocument(text, 1, "manual", 0, "[]",
                    List.of(new ExtractedSegment(1, 1, text, text, 0, text.length())));
        }
        String lowerName = fileName.toLowerCase(java.util.Locale.ROOT);
        if (!(lowerName.endsWith(".pdf") || lowerName.endsWith(".png")
                || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg"))) {
            throw new BusinessException(400, "仅支持 PDF、PNG、JPG 文件");
        }
        Map<String, Object> result = aiClient.extractText(target, fileName, contentType);
        String text = String.valueOf(result.getOrDefault("text", "")).trim();
        if (text.isBlank()) {
            throw new BusinessException(400, "文件未提取到可读文本，请检查清晰度、页面方向或 OCR 语言包");
        }
        List<ExtractedSegment> segments = new ArrayList<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) result.getOrDefault("segments", List.of())) {
            segments.add(new ExtractedSegment(
                    number(item.get("page_no")),
                    number(item.get("segment_no")),
                    String.valueOf(item.get("text")),
                    String.valueOf(item.getOrDefault("raw_text", item.get("text"))),
                    number(item.get("start_offset")),
                    number(item.get("end_offset"))));
        }
        int pageCount = number(result.getOrDefault("page_count", segments.size()));
        String method = String.valueOf(result.getOrDefault("extraction_method", "pymupdf"));
        if (!Set.of("pymupdf", "ocr", "pymupdf+ocr").contains(method)) method = "unknown";
        int ocrPageCount = number(result.getOrDefault("ocr_page_count", 0));
        String qualityJson;
        try {
            qualityJson = objectMapper.writeValueAsString(result.getOrDefault("quality_pages", List.of()));
        } catch (JsonProcessingException exception) {
            qualityJson = "[]";
        }
        return new ExtractedDocument(text, pageCount, method, ocrPageCount, qualityJson, segments);
    }

    private List<PreparedField> prepareFields(long documentId, Map<String, Object> result) {
        if (result == null || !(result.get("fields") instanceof List<?> rawFields) || rawFields.isEmpty()) {
            return List.of();
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

    private static boolean hasReviewableContent(Map<String, Object> result) {
        if (result == null) return false;
        for (String key : List.of(
                "fields", "standard_sections", "policy_sections", "health_guidance",
                "action_checklist", "key_facts", "service_schedule", "conditional_materials",
                "faq", "scope", "summary", "plain_text")) {
            Object value = result.get(key);
            if (value instanceof String text && !text.isBlank()) return true;
            if (value instanceof List<?> list && !list.isEmpty()) return true;
            if (value instanceof Map<?, ?> map && !map.isEmpty()) return true;
        }
        return false;
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

    private record ExtractedDocument(String text, int pageCount, String method, int ocrPageCount,
                                     String qualityJson, List<ExtractedSegment> segments) {}

    private record ExtractedSegment(int pageNo, int segmentNo, String text, String rawText,
                                    int startOffset, int endOffset) {}

    private record SourceTrace(long segmentId, int pageNo, String quote) {}

    private record PreparedField(Map<String, Object> field, SourceTrace source, double confidence) {}

    public record OriginalFile(Path path, String filename, String mimeType, long size, String sha256) {}

    private static final class AiResultValidationException extends RuntimeException {}
}
