package cn.jianda.collector;

import cn.jianda.common.BusinessException;
import cn.jianda.security.AuthUser;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CrawlTaskService {
    public static final Set<String> STATUSES = Set.of(
            "PENDING", "RUNNING", "SUCCESS", "PARTIAL_SUCCESS", "FAILED", "CANCELLED", "DISABLED");
    private static final int MAX_RETRIES = 3;
    private static final String JOB_COLUMNS = "j.id,j.parent_job_id,j.source_registry_id,j.document_id,j.original_url,"
            + "j.canonical_url,j.status,j.trigger_type,j.processing_stage,j.discovery_method,j.discovery_page,"
            + "j.discovered_at,j.started_at,j.finished_at,j.duration_ms,j.discovered_count,j.added_count,"
            + "j.duplicate_count,j.skipped_count,j.failed_count,j.retry_count,j.error_type,j.last_error,"
            + "j.lock_owner,j.created_by,j.scheduler_identity,j.created_at,j.updated_at,r.source_name,r.domain";
    private static final String ERROR_COLUMNS = "id,crawl_job_id,source_registry_id,failed_url,processing_stage,"
            + "error_code,error_summary,retryable,retry_count,next_retry_at,resolved_at,created_at,updated_at";

    private final JdbcTemplate jdbc;
    private final SourceRegistryService sourceRegistryService;

    public CrawlTaskService(JdbcTemplate jdbc, SourceRegistryService sourceRegistryService) {
        this.jdbc = jdbc;
        this.sourceRegistryService = sourceRegistryService;
    }

    public List<Map<String, Object>> jobs(String status, Long sourceId) {
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ").append(JOB_COLUMNS)
                .append(" FROM crawl_job j JOIN source_registry r ON r.id=j.source_registry_id WHERE 1=1 ");
        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(normalized)) throw new BusinessException(400, "任务状态不正确");
            sql.append("AND j.status=? ");
            parameters.add(normalized);
        }
        if (sourceId != null) {
            sql.append("AND j.source_registry_id=? ");
            parameters.add(sourceId);
        }
        sql.append("ORDER BY j.created_at DESC,j.id DESC LIMIT 200");
        return jdbc.queryForList(sql.toString(), parameters.toArray());
    }

    public Map<String, Object> detail(long jobId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + JOB_COLUMNS + " FROM crawl_job j JOIN source_registry r ON r.id=j.source_registry_id WHERE j.id=?",
                jobId);
        if (rows.isEmpty()) throw new BusinessException(404, "采集任务不存在");
        Map<String, Object> result = new java.util.LinkedHashMap<>(rows.get(0));
        result.put("errors", errors(jobId));
        return result;
    }

    public List<Map<String, Object>> errors(long jobId) {
        detailExists(jobId);
        return jdbc.queryForList("SELECT " + ERROR_COLUMNS + " FROM crawl_job_error WHERE crawl_job_id=? "
                + "ORDER BY created_at,id", jobId);
    }

    public boolean hasCanonical(long sourceId, String canonicalUrl) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crawl_job WHERE source_registry_id=? AND canonical_url=? "
                        + "AND status IN ('PENDING','RUNNING','SUCCESS','PARTIAL_SUCCESS')",
                Integer.class, sourceId, canonicalUrl);
        return count != null && count > 0;
    }

    @Transactional
    public long createBatch(long sourceId, String entryUrl, String triggerType, String method,
            AuthUser user, String schedulerIdentity) {
        String trigger = normalizeTrigger(triggerType);
        Integer enabled = jdbc.queryForObject("SELECT COUNT(*) FROM source_registry WHERE id=? AND enabled=TRUE",
                Integer.class, sourceId);
        String status = enabled != null && enabled > 0 ? "PENDING" : "DISABLED";
        return insertJob(null, sourceId, entryUrl, null, status, trigger, method, entryUrl,
                user == null ? null : user.id(), schedulerIdentity, 0);
    }

    @Transactional
    public long create(long sourceId, String originalUrl, String canonicalUrl, String triggerType,
            String method, String discoveryPage, AuthUser user, String schedulerIdentity) {
        String trigger = normalizeTrigger(triggerType);
        if (user == null && (schedulerIdentity == null || schedulerIdentity.isBlank())) {
            throw new BusinessException(400, "任务必须记录创建人或 scheduler 标识");
        }
        Integer enabled = jdbc.queryForObject("SELECT COUNT(*) FROM source_registry WHERE id=? AND enabled=TRUE",
                Integer.class, sourceId);
        if (enabled == null || enabled == 0) return createDisabled(sourceId, originalUrl, canonicalUrl, trigger, user, schedulerIdentity);
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id,status FROM crawl_job WHERE source_registry_id=? AND canonical_url=?", sourceId, canonicalUrl);
        if (!existing.isEmpty()) return ((Number) existing.get(0).get("id")).longValue();
        return insertJob(null, sourceId, originalUrl, canonicalUrl, "PENDING", trigger, method, discoveryPage,
                user == null ? null : user.id(), schedulerIdentity, 0);
    }

    @Transactional
    public void start(long jobId, String owner) {
        Map<String, Object> job = job(jobId);
        long sourceId = ((Number) job.get("source_registry_id")).longValue();
        if (!sourceRegistryService.acquireLease(sourceId, owner, java.time.Duration.ofMinutes(10))) {
            throw new BusinessException(409, "同一来源已有采集任务正在运行");
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        Timestamp until = Timestamp.valueOf(LocalDateTime.now().plusMinutes(10));
        int changed = jdbc.update("UPDATE crawl_job SET status='RUNNING',started_at=?,lock_owner=?,"
                + "lock_until=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",
                now, owner, until, jobId);
        if (changed == 0) {
            sourceRegistryService.releaseLease(sourceId, owner, "CANCELLED", "任务状态已变化");
            throw new BusinessException(409, "任务不是等待运行状态");
        }
    }

    @Transactional
    public void finish(long jobId, String owner, Counts counts, List<Failure> failures) {
        Map<String, Object> job = job(jobId);
        if (!"RUNNING".equals(job.get("status")) || !owner.equals(job.get("lock_owner"))) {
            throw new BusinessException(409, "任务锁持有者或运行状态不匹配");
        }
        long sourceId = ((Number) job.get("source_registry_id")).longValue();
        List<Failure> safeFailures = failures == null ? List.of() : failures;
        for (Failure failure : safeFailures) addError(jobId, sourceId, failure);
        int failed = Math.max(counts.failed(), safeFailures.size());
        String status = failed == 0 ? "SUCCESS" : counts.added() + counts.duplicate() + counts.skipped() > 0
                ? "PARTIAL_SUCCESS" : "FAILED";
        String summary = safeFailures.isEmpty() ? null : safeSummary(safeFailures.get(0).summary());
        Timestamp finishedAt = Timestamp.valueOf(LocalDateTime.now());
        Object startedValue = job.get("started_at");
        long durationMs = startedValue instanceof java.util.Date started
                ? Math.max(0, finishedAt.getTime() - started.getTime()) : 0;
        jdbc.update("UPDATE crawl_job SET status=?,finished_at=?,duration_ms=?,"
                        + "discovered_count=?,added_count=?,duplicate_count=?,skipped_count=?,failed_count=?,last_error=?,"
                        + "error_type=?,lock_owner=NULL,lock_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND lock_owner=?",
                status, finishedAt, durationMs, counts.discovered(), counts.added(), counts.duplicate(), counts.skipped(), failed, summary,
                safeFailures.isEmpty() ? null : safeCode(safeFailures.get(0).code()), jobId, owner);
        sourceRegistryService.releaseLease(sourceId, owner, status, summary);
    }

    @Transactional
    public void cancel(long jobId) {
        Map<String, Object> job = job(jobId);
        if (!Set.of("PENDING", "RUNNING").contains(String.valueOf(job.get("status")))) {
            throw new BusinessException(409, "当前采集任务已经结束，不能取消");
        }
        String owner = job.get("lock_owner") == null ? null : String.valueOf(job.get("lock_owner"));
        jdbc.update("UPDATE crawl_job SET status='CANCELLED',finished_at=CURRENT_TIMESTAMP,lock_owner=NULL,lock_until=NULL,"
                + "updated_at=CURRENT_TIMESTAMP WHERE id=?", jobId);
        if (owner != null) sourceRegistryService.releaseLease(
                ((Number) job.get("source_registry_id")).longValue(), owner, "CANCELLED", "管理员取消任务");
    }

    @Transactional
    public long retryError(long errorId, AuthUser user) {
        Map<String, Object> error = error(errorId);
        validateRetry(error);
        long sourceId = ((Number) error.get("source_registry_id")).longValue();
        String url = String.valueOf(error.get("failed_url"));
        List<Map<String, Object>> successful = jdbc.queryForList(
                "SELECT id FROM crawl_job WHERE source_registry_id=? AND canonical_url=? AND status='SUCCESS'", sourceId, url);
        if (!successful.isEmpty()) throw new BusinessException(409, "该地址已经成功处理，无需重试");
        long parentId = ((Number) error.get("crawl_job_id")).longValue();
        int retryCount = ((Number) error.get("retry_count")).intValue() + 1;
        long childId = insertJob(parentId, sourceId, url, url, "PENDING", "RETRY", null, null, user.id(), null, retryCount);
        jdbc.update("UPDATE crawl_job_error SET retry_count=?,next_retry_at=?,resolved_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                retryCount, nextRetry(retryCount), errorId);
        return childId;
    }

    @Transactional
    public List<Long> retryBatch(long jobId, AuthUser user) {
        detailExists(jobId);
        List<Map<String, Object>> pending = jdbc.queryForList("SELECT " + ERROR_COLUMNS
                + " FROM crawl_job_error WHERE crawl_job_id=? AND resolved_at IS NULL ORDER BY id", jobId);
        List<Long> result = new ArrayList<>();
        for (Map<String, Object> error : pending) {
            try {
                result.add(retryError(((Number) error.get("id")).longValue(), user));
            } catch (BusinessException exception) {
                if (exception.getCode() != 409) throw exception;
            }
        }
        if (result.isEmpty()) throw new BusinessException(409, "没有可重试的失败条目");
        return result;
    }

    private void addError(long jobId, long sourceId, Failure failure) {
        jdbc.update("INSERT INTO crawl_job_error(crawl_job_id,source_registry_id,failed_url,processing_stage,error_code,"
                        + "error_summary,retryable,retry_count,next_retry_at) VALUES (?,?,?,?,?,?,?,?,?)",
                jobId, sourceId, safeUrl(failure.url()), safeStage(failure.stage()), safeCode(failure.code()),
                safeSummary(failure.summary()), failure.retryable(), 0, failure.retryable() ? nextRetry(1) : null);
    }

    private void validateRetry(Map<String, Object> error) {
        if (!Boolean.TRUE.equals(error.get("retryable"))) throw new BusinessException(409, "该错误不可重试");
        if (error.get("resolved_at") != null) throw new BusinessException(409, "该失败条目已经进入重试流程");
        if (((Number) error.get("retry_count")).intValue() >= MAX_RETRIES) throw new BusinessException(409, "已达到最大重试次数");
    }

    private long createDisabled(long sourceId, String originalUrl, String canonicalUrl, String trigger,
            AuthUser user, String schedulerIdentity) {
        return insertJob(null, sourceId, originalUrl, canonicalUrl, "DISABLED", trigger, null, null,
                user == null ? null : user.id(), schedulerIdentity, 0);
    }

    private long insertJob(Long parentId, long sourceId, String originalUrl, String canonicalUrl, String status,
            String trigger, String method, String discoveryPage, Long createdBy, String schedulerIdentity, int retryCount) {
        if (canonicalUrl != null && !canonicalUrl.isBlank()) {
            List<Map<String, Object>> existing = jdbc.queryForList(
                    "SELECT id,status FROM crawl_job WHERE source_registry_id=? AND canonical_url=?", sourceId, canonicalUrl);
            if (!existing.isEmpty()) {
                Map<String, Object> row = existing.get(0);
                String current = String.valueOf(row.get("status"));
                if (Set.of("PENDING", "RUNNING", "SUCCESS", "PARTIAL_SUCCESS").contains(current)) {
                    return ((Number) row.get("id")).longValue();
                }
                jdbc.update("UPDATE crawl_job SET parent_job_id=?,original_url=?,status=?,trigger_type=?,processing_stage='DISCOVERY',"
                                + "discovery_method=?,discovery_page=?,retry_count=?,created_by=?,scheduler_identity=?,"
                                + "started_at=NULL,finished_at=NULL,last_error=NULL,error_type=NULL,lock_owner=NULL,lock_until=NULL,"
                                + "updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        parentId, originalUrl, status, trigger, method, discoveryPage, retryCount, createdBy,
                        schedulerIdentity, row.get("id"));
                return ((Number) row.get("id")).longValue();
            }
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO crawl_job(parent_job_id,source_registry_id,original_url,canonical_url,status,trigger_type,"
                            + "processing_stage,discovery_method,discovery_page,discovered_at,retry_count,created_by,scheduler_identity) "
                            + "VALUES (?,?,?,?,?,?,'DISCOVERY',?,?,CURRENT_TIMESTAMP,?,?,?)", new String[] {"id"});
            statement.setObject(1, parentId);
            statement.setLong(2, sourceId);
            statement.setString(3, safeUrl(originalUrl));
            statement.setString(4, canonicalUrl == null ? null : safeUrl(canonicalUrl));
            statement.setString(5, status);
            statement.setString(6, trigger);
            statement.setString(7, method);
            statement.setString(8, discoveryPage);
            statement.setInt(9, retryCount);
            statement.setObject(10, createdBy);
            statement.setString(11, schedulerIdentity);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("未取得采集任务编号");
        return key.longValue();
    }

    private Map<String, Object> job(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM crawl_job WHERE id=?", id);
        if (rows.isEmpty()) throw new BusinessException(404, "采集任务不存在");
        return rows.get(0);
    }

    private Map<String, Object> error(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + ERROR_COLUMNS + " FROM crawl_job_error WHERE id=?", id);
        if (rows.isEmpty()) throw new BusinessException(404, "失败条目不存在");
        return rows.get(0);
    }

    private void detailExists(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM crawl_job WHERE id=?", Integer.class, id);
        if (count == null || count == 0) throw new BusinessException(404, "采集任务不存在");
    }

    private static String normalizeTrigger(String value) {
        String trigger = value == null ? "MANUAL" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("MANUAL", "SCHEDULED", "RETRY").contains(trigger)) throw new BusinessException(400, "触发方式不正确");
        return trigger;
    }

    private static Timestamp nextRetry(int retryCount) {
        long minutes = Math.min(60, 1L << Math.min(retryCount, 6));
        return Timestamp.valueOf(LocalDateTime.now().plusMinutes(minutes));
    }

    private static String safeStage(String value) {
        String stage = value == null ? "DISCOVERY" : value.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
        return stage.substring(0, Math.min(40, stage.length()));
    }

    private static String safeCode(String value) {
        String code = value == null ? "UNKNOWN_ERROR" : value.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
        return code.substring(0, Math.min(80, code.length()));
    }

    private static String safeSummary(String value) {
        String safe = value == null ? "处理失败" : value
                .replaceAll("(?i)authorization\\s*[:=]?\\s*(?:bearer\\s+)?[^\\s,;]+", "Authorization=[已脱敏]")
                .replaceAll("(?i)(cookie|api[-_ ]?key)\\s*[:=]?\\s*[^\\s,;]+", "$1=[已脱敏]")
                .replaceAll("(?i)bearer\\s+[^\\s,;]+", "Bearer [已脱敏]")
                .replaceAll("(?i)sk-[A-Za-z0-9_-]{8,}", "[已脱敏]")
                .replaceAll("(?s)\\s+at\\s+[A-Za-z0-9_.$]+\\([^)]*\\)", "")
                .trim();
        if (safe.isBlank()) safe = "处理失败";
        return safe.substring(0, Math.min(500, safe.length()));
    }

    private static String safeUrl(String value) {
        if (value == null) return null;
        try {
            java.net.URI uri = java.net.URI.create(value.trim());
            java.net.URI safe = new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), null);
            String result = safe.toString();
            return result.substring(0, Math.min(1000, result.length()));
        } catch (Exception exception) {
            return "";
        }
    }

    public record Counts(int discovered, int added, int duplicate, int skipped, int failed) {
        public Counts {
            if (discovered < 0 || added < 0 || duplicate < 0 || skipped < 0 || failed < 0) {
                throw new IllegalArgumentException("任务计数不能为负数");
            }
        }
    }

    public record Failure(String url, String stage, String code, String summary, boolean retryable) {}
}
