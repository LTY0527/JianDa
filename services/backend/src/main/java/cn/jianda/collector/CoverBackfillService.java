package cn.jianda.collector;

import cn.jianda.ai.AiClient;
import cn.jianda.common.BusinessException;
import cn.jianda.security.AuthUser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoverBackfillService {
    private final JdbcTemplate jdbc;
    private final AiClient aiClient;
    private final WebArticleService webArticleService;
    private final ImageCandidateService imageCandidateService;
    private final Path uploadRoot;
    private final boolean enabled;
    private final AtomicLong jobSequence = new AtomicLong();
    private final ConcurrentMap<Long, Map<String, Object>> jobs =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, List<Map<String, Object>>> jobItems =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, AuthUser> jobUsers =
            new ConcurrentHashMap<>();

    public CoverBackfillService(
            JdbcTemplate jdbc, AiClient aiClient, WebArticleService webArticleService,
            ImageCandidateService imageCandidateService,
            @Value("${jianda.upload-dir}") String uploadDir,
            @Value("${jianda.crawl.historical-cover-backfill-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.aiClient = aiClient;
        this.webArticleService = webArticleService;
        this.imageCandidateService = imageCandidateService;
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.enabled = enabled;
    }

    public Map<String, Object> preview(BackfillFilter filter) {
        assertEnabled();
        List<Map<String, Object>> items = candidates(filter);
        Map<String, Long> byType = new LinkedHashMap<>();
        for (String type : List.of("WEB_ARTICLE", "PDF", "IMAGE")) {
            byType.put(type, items.stream().filter(item -> type.equals(item.get("source_type"))).count());
        }
        return Map.of("total", items.size(), "byType", byType, "items",
                items.stream().limit(100).map(this::safeSummary).toList(), "preview", true);
    }

    public Map<String, Object> execute(BackfillFilter filter, AuthUser user) {
        assertEnabled();
        List<Map<String, Object>> items = candidates(filter);
        int updated = 0;
        int candidatesCreated = 0;
        int autoApproved = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Map<String, Object> item : items.stream().limit(100).toList()) {
            long documentId = ((Number) item.get("id")).longValue();
            try {
                String sourceType = text(item.get("source_type"));
                if ("PDF".equals(sourceType)) {
                    createPdfCover(item, user);
                    updated++;
                } else if ("IMAGE".equals(sourceType)) {
                    useUploadedImage(item, user);
                    updated++;
                } else if ("WEB_ARTICLE".equals(sourceType)) {
                    int before = pendingCandidates(documentId);
                    webArticleService.rescanImageCandidates(documentId, user);
                    int after = pendingCandidates(documentId);
                    candidatesCreated += Math.max(0, after - before);
                    if (isVerifiedWebDocument(documentId)
                            && imageCandidateService.autoApproveFirst(documentId, user)) {
                        autoApproved++;
                        updated++;
                    }
                }
            } catch (RuntimeException exception) {
                errors.add(Map.of("documentId", documentId, "message", safeMessage(exception)));
            }
        }
        jdbc.update("INSERT INTO operation_log(operator_id,organization_id,action,target_type,target_id,result,ip) "
                        + "VALUES (?,?,?,'COVER_BACKFILL',?,'SUCCESS','local')",
                user.id(), user.organizationId(), "RUN_HISTORICAL_COVER_BACKFILL", 0);
        return Map.of("scanned", Math.min(items.size(), 100), "updated", updated,
                "candidatesCreated", candidatesCreated, "autoApproved", autoApproved,
                "failed", errors.size(), "errors", errors);
    }

    public Map<String, Object> startJob(BackfillFilter filter, AuthUser user) {
        assertEnabled();
        List<Map<String, Object>> items =
                candidates(filter).stream().limit(100).toList();
        long jobId = jobSequence.incrementAndGet();
        Map<String, Object> state = initialJob(jobId, items.size());
        jobs.put(jobId, state);
        jobItems.put(jobId, items);
        jobUsers.put(jobId, user);
        CompletableFuture.runAsync(() -> runJob(jobId, items, user));
        return new LinkedHashMap<>(state);
    }

    public Map<String, Object> job(long jobId) {
        Map<String, Object> state = jobs.get(jobId);
        if (state == null) throw new BusinessException(404, "历史补图任务不存在");
        synchronized (state) {
            return new LinkedHashMap<>(state);
        }
    }

    public Map<String, Object> retry(long jobId, long documentId) {
        Map<String, Object> state = jobs.get(jobId);
        List<Map<String, Object>> items = jobItems.get(jobId);
        AuthUser user = jobUsers.get(jobId);
        if (state == null || items == null || user == null) {
            throw new BusinessException(404, "历史补图任务不存在");
        }
        Map<String, Object> item = items.stream()
                .filter(candidate -> ((Number) candidate.get("id")).longValue() == documentId)
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "该失败材料不属于此补图任务"));
        synchronized (state) {
            state.put("status", "RUNNING");
            state.put("currentDocumentId", documentId);
            state.put("currentDocumentTitle", text(item.get("title")));
        }
        CompletableFuture.runAsync(() -> {
            try {
                ProcessResult result = processItem(item, user);
                synchronized (state) {
                    state.put("updated", integer(state.get("updated")) + result.updated());
                    state.put("candidatesCreated",
                            integer(state.get("candidatesCreated")) + result.candidatesCreated());
                    state.put("autoApproved",
                            integer(state.get("autoApproved")) + result.autoApproved());
                    removeError(state, documentId);
                    state.put("failed", errorCount(state));
                    state.put("status", "SUCCEEDED");
                    clearCurrent(state);
                }
            } catch (RuntimeException exception) {
                synchronized (state) {
                    replaceError(state, documentId, safeMessage(exception));
                    state.put("failed", errorCount(state));
                    state.put("status", "PARTIAL_SUCCESS");
                    clearCurrent(state);
                }
            }
        });
        return job(jobId);
    }

    private void runJob(long jobId, List<Map<String, Object>> items, AuthUser user) {
        Map<String, Object> state = jobs.get(jobId);
        synchronized (state) {
            state.put("status", "RUNNING");
        }
        for (int batchStart = 0; batchStart < items.size(); batchStart += 5) {
            List<Map<String, Object>> batch =
                    items.subList(batchStart, Math.min(batchStart + 5, items.size()));
            for (Map<String, Object> item : batch) {
                long documentId = ((Number) item.get("id")).longValue();
                synchronized (state) {
                    state.put("currentDocumentId", documentId);
                    state.put("currentDocumentTitle", text(item.get("title")));
                }
                try {
                    ProcessResult result = processItem(item, user);
                    synchronized (state) {
                        state.put("updated", integer(state.get("updated")) + result.updated());
                        state.put("candidatesCreated",
                                integer(state.get("candidatesCreated")) + result.candidatesCreated());
                        state.put("autoApproved",
                                integer(state.get("autoApproved")) + result.autoApproved());
                    }
                } catch (RuntimeException exception) {
                    synchronized (state) {
                        addError(state, documentId, safeMessage(exception));
                        state.put("failed", integer(state.get("failed")) + 1);
                    }
                } finally {
                    synchronized (state) {
                        state.put("processed", integer(state.get("processed")) + 1);
                    }
                }
            }
        }
        synchronized (state) {
            state.put("status", integer(state.get("failed")) > 0
                    ? "PARTIAL_SUCCESS" : "SUCCEEDED");
            clearCurrent(state);
        }
    }

    private ProcessResult processItem(Map<String, Object> item, AuthUser user) {
        long documentId = ((Number) item.get("id")).longValue();
        String sourceType = text(item.get("source_type"));
        if ("PDF".equals(sourceType)) {
            createPdfCover(item, user);
            return new ProcessResult(1, 0, 0);
        }
        if ("IMAGE".equals(sourceType)) {
            useUploadedImage(item, user);
            return new ProcessResult(1, 0, 0);
        }
        if ("WEB_ARTICLE".equals(sourceType)) {
            int before = pendingCandidates(documentId);
            webArticleService.rescanImageCandidates(documentId, user);
            int created = Math.max(0, pendingCandidates(documentId) - before);
            boolean approved = isVerifiedWebDocument(documentId)
                    && imageCandidateService.autoApproveFirst(documentId, user);
            return new ProcessResult(approved ? 1 : 0, created, approved ? 1 : 0);
        }
        throw new BusinessException(400, "不支持的历史材料类型");
    }

    private boolean isVerifiedWebDocument(long documentId) {
        Boolean verified = jdbc.queryForObject(
                "SELECT external_source_verified FROM source_document WHERE id=?",
                Boolean.class, documentId);
        return Boolean.TRUE.equals(verified);
    }

    private static Map<String, Object> initialJob(long jobId, int total) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("jobId", jobId);
        state.put("status", "PENDING");
        state.put("total", total);
        state.put("processed", 0);
        state.put("updated", 0);
        state.put("candidatesCreated", 0);
        state.put("autoApproved", 0);
        state.put("failed", 0);
        state.put("currentDocumentId", null);
        state.put("currentDocumentTitle", "");
        state.put("errors", new ArrayList<Map<String, Object>>());
        return state;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(Map<String, Object> state) {
        return (List<Map<String, Object>>) state.get("errors");
    }

    private static void addError(
            Map<String, Object> state, long documentId, String message) {
        errors(state).add(Map.of("documentId", documentId, "message", message));
    }

    private static void replaceError(
            Map<String, Object> state, long documentId, String message) {
        removeError(state, documentId);
        addError(state, documentId, message);
    }

    private static void removeError(Map<String, Object> state, long documentId) {
        errors(state).removeIf(error ->
                ((Number) error.get("documentId")).longValue() == documentId);
    }

    private static int errorCount(Map<String, Object> state) {
        return errors(state).size();
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static void clearCurrent(Map<String, Object> state) {
        state.put("currentDocumentId", null);
        state.put("currentDocumentTitle", "");
    }

    private List<Map<String, Object>> candidates(BackfillFilter rawFilter) {
        BackfillFilter filter = rawFilter == null
                ? new BackfillFilter(true, null, null, null, null, null) : rawFilter;
        StringBuilder sql = new StringBuilder(
                "SELECT d.id,d.title,d.source_type,d.content_kind,d.category,d.storage_path,d.original_filename,"
                        + "d.mime_type,d.file_sha256,d.original_url,d.canonical_url,d.cover_image_type,"
                        + "d.cover_image_url,d.custom_cover_path,d.image_reviewed,d.imported_at,p.status publish_status "
                        + "FROM source_document d LEFT JOIN published_item p ON p.document_id=d.id WHERE "
                        + "d.source_type IN ('WEB_ARTICLE','PDF','IMAGE')");
        List<Object> parameters = new ArrayList<>();
        if (Boolean.TRUE.equals(filter.onlyMissing())) {
            sql.append(" AND (d.custom_cover_path IS NULL OR d.cover_image_type='CATEGORY_DEFAULT')");
        }
        if (filter.sourceId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM crawl_job j WHERE j.document_id=d.id AND j.source_registry_id=?)");
            parameters.add(filter.sourceId());
        }
        if (filter.contentKind() != null && !filter.contentKind().isBlank()) {
            sql.append(" AND d.content_kind=?");
            parameters.add(filter.contentKind().trim());
        }
        if (filter.publishStatus() != null && !filter.publishStatus().isBlank()) {
            sql.append(" AND p.status=?");
            parameters.add(filter.publishStatus().trim().toUpperCase());
        }
        if (filter.fromDate() != null) {
            sql.append(" AND d.imported_at>=?");
            parameters.add(Date.valueOf(filter.fromDate()));
        }
        if (filter.toDate() != null) {
            sql.append(" AND d.imported_at<?");
            parameters.add(Date.valueOf(filter.toDate().plusDays(1)));
        }
        sql.append(" ORDER BY d.id LIMIT 500");
        return jdbc.queryForList(sql.toString(), parameters.toArray());
    }

    @Transactional
    protected void createPdfCover(Map<String, Object> item, AuthUser user) {
        Path original = safeStoredPath(item);
        byte[] png = aiClient.renderPdfFirstPage(original, text(item.get("original_filename")));
        if (png.length < 8 || png[0] != (byte) 0x89 || png[1] != 0x50 || png[2] != 0x4e || png[3] != 0x47) {
            throw new BusinessException(502, "PDF 第一页渲染未返回有效 PNG");
        }
        long documentId = ((Number) item.get("id")).longValue();
        Path target = coverPath(documentId, ".png");
        write(target, png);
        updateLocalCover(documentId, target, "image/png", "pdf-first-page.png",
                "PDF_FIRST_PAGE", text(item.get("original_filename")),
                "由已上传原文件第一页自动生成", sha256(png), user);
    }

    @Transactional
    protected void useUploadedImage(Map<String, Object> item, AuthUser user) {
        Path original = safeStoredPath(item);
        long documentId = ((Number) item.get("id")).longValue();
        String filename = text(item.get("original_filename"));
        String mime = text(item.get("mime_type"));
        updateLocalCover(documentId, original, mime, filename, "UPLOADED_ORIGINAL", filename,
                "使用机构已上传并有权使用的原图片", text(item.get("file_sha256")), user);
    }

    private void updateLocalCover(
            long documentId, Path path, String mime, String filename, String coverType,
            String sourceName, String licenseNote, String hash, AuthUser user) {
        jdbc.update("UPDATE source_document SET custom_cover_path=?,custom_cover_mime=?,custom_cover_filename=?,"
                        + "cover_image_url=NULL,cover_image_type=?,image_source_name=?,image_source_url=NULL,"
                        + "image_cached=TRUE,image_reviewed=TRUE,image_license_note=?,image_hash=? WHERE id=?",
                path.toString(), mime, filename, coverType, sourceName, licenseNote, hash, documentId);
        jdbc.update("UPDATE published_item SET cover_image_url=CONCAT('/api/public/items/',slug,'/cover') "
                + "WHERE document_id=? AND status='PUBLISHED'", documentId);
        jdbc.update("INSERT INTO operation_log(operator_id,organization_id,action,target_type,target_id,result,ip) "
                        + "VALUES (?,?,?,'SOURCE_DOCUMENT',?,'SUCCESS','cover-policy')",
                user.id(), user.organizationId(), "AUTO_CONFIRM_" + coverType, documentId);
    }

    private void write(Path target, byte[] bytes) {
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException exception) {
            throw new BusinessException(500, "封面文件写入失败");
        }
    }

    private Path coverPath(long documentId, String extension) {
        Path directory = uploadRoot.resolve("covers").resolve("backfill").resolve(String.valueOf(documentId)).normalize();
        Path target = directory.resolve(UUID.randomUUID() + extension).normalize();
        if (!target.startsWith(uploadRoot)) throw new BusinessException(400, "封面路径不安全");
        return target;
    }

    private Path safeStoredPath(Map<String, Object> item) {
        String stored = text(item.get("storage_path"));
        if (stored.isBlank()) throw new BusinessException(404, "原文件不存在");
        Path path = Paths.get(stored).toAbsolutePath().normalize();
        if (!path.startsWith(uploadRoot) || !Files.isRegularFile(path)) {
            throw new BusinessException(404, "原文件不存在或路径不安全");
        }
        return path;
    }

    private int pendingCandidates(long documentId) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM image_candidate WHERE document_id=? AND review_status='PENDING'",
                Integer.class, documentId);
        return value == null ? 0 : value;
    }

    private Map<String, Object> safeSummary(Map<String, Object> item) {
        return Map.of(
                "documentId", item.get("id"),
                "title", text(item.get("title")),
                "sourceType", text(item.get("source_type")),
                "contentKind", text(item.get("content_kind")),
                "coverType", text(item.get("cover_image_type")),
                "publishStatus", text(item.get("publish_status")));
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "封面补齐失败";
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void assertEnabled() {
        if (!enabled) {
            throw new BusinessException(503, "历史封面补齐能力当前未启用");
        }
    }

    public record BackfillFilter(Boolean onlyMissing, Long sourceId, String contentKind,
            String publishStatus, LocalDate fromDate, LocalDate toDate) {}

    private record ProcessResult(
            int updated, int candidatesCreated, int autoApproved) {}
}
