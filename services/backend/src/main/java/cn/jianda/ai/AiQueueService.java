package cn.jianda.ai;

import cn.jianda.common.BusinessException;
import cn.jianda.security.AuthUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiQueueService {
    public static final String QUEUED = "QUEUED";
    public static final String WAITING_BUDGET = "WAITING_BUDGET";
    public static final String WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String DUPLICATE = "DUPLICATE";

    private final JdbcTemplate jdbc;
    private final boolean autoEnabled;
    private final int globalArticleLimit;
    private final int globalTokenLimit;
    private final int maxInputCharacters;
    private final int maxArticlesPerTask;
    private final ZoneId zoneId;
    private Clock clock = Clock.systemUTC();

    public AiQueueService(JdbcTemplate jdbc,
            @Value("${jianda.crawl.auto-ai-enabled:false}") boolean autoEnabled,
            @Value("${jianda.crawl.daily-ai-max-articles:5}") int globalArticleLimit,
            @Value("${jianda.crawl.daily-ai-max-tokens:50000}") int globalTokenLimit,
            @Value("${jianda.crawl.ai-max-input-characters:50000}") int maxInputCharacters,
            @Value("${jianda.crawl.ai-max-articles-per-task:5}") int maxArticlesPerTask,
            @Value("${jianda.crawl.timezone:Asia/Shanghai}") String timezone) {
        this.jdbc = jdbc;
        this.autoEnabled = autoEnabled;
        this.globalArticleLimit = Math.max(0, globalArticleLimit);
        this.globalTokenLimit = Math.max(0, globalTokenLimit);
        this.maxInputCharacters = Math.max(1, maxInputCharacters);
        this.maxArticlesPerTask = Math.max(1, maxArticlesPerTask);
        this.zoneId = ZoneId.of(timezone);
    }

    @Transactional
    public Map<String, Object> enqueue(long documentId, Long sourceId, Long crawlJobId) {
        Map<String, Object> document = document(documentId);
        String text = text(document.get("raw_text"));
        String hash = contentHash(document, text);
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT * FROM ai_processing_queue WHERE content_hash=?", hash);
        if (!existing.isEmpty()) {
            audit(sourceId, crawlJobId, documentId, id(existing.get(0)), "CONTENT_HASH_DUPLICATE",
                    "DUPLICATE", 0, 0, false, null, null, DUPLICATE);
            return view(existing.get(0));
        }
        int estimated = estimateTokens(text);
        String status = autoEnabled ? QUEUED : WAITING_APPROVAL;
        String reason = autoEnabled ? null : "AUTO_AI_DISABLED";
        String summary = autoEnabled ? null : "自动 AI 默认关闭，正文与版本已保留，等待人工批准";
        Long taskRootId = crawlJobId == null ? null : jdbc.queryForObject(
                "SELECT COALESCE(parent_job_id,id) FROM crawl_job WHERE id=?", Long.class, crawlJobId);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_processing_queue(source_registry_id,crawl_job_id,crawl_task_root_id,document_id,content_hash,status,"
                            + "reason_code,reason_summary,estimated_tokens,actual_tokens,available_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,0,?)", new String[] {"id"});
            statement.setObject(1, sourceId);
            statement.setObject(2, crawlJobId);
            statement.setObject(3, taskRootId);
            statement.setLong(4, documentId);
            statement.setString(5, hash);
            statement.setString(6, status);
            statement.setString(7, reason);
            statement.setString(8, summary);
            statement.setInt(9, estimated);
            statement.setTimestamp(10, status.equals(QUEUED) ? Timestamp.from(now()) : null);
            return statement;
        }, keys);
        Number generatedQueueId = keys.getKey();
        if (generatedQueueId == null) throw new IllegalStateException("未取得 AI 队列编号");
        long queueId = generatedQueueId.longValue();
        audit(sourceId, crawlJobId, documentId, queueId, reason, null, estimated, 0,
                false, null, null, status);
        return get(queueId);
    }

    public Map<String, Object> automationOwner(long documentId) {
        return jdbc.queryForMap("SELECT u.id,u.organization_id,o.name organization_name FROM source_document d "
                + "JOIN staff_user u ON u.id=d.created_by JOIN organization o ON o.id=u.organization_id WHERE d.id=?",
                documentId);
    }

    public Map<String, Object> get(long queueId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT q.*,r.source_name FROM ai_processing_queue q LEFT JOIN source_registry r "
                        + "ON r.id=q.source_registry_id WHERE q.id=?", queueId);
        if (rows.isEmpty()) throw new BusinessException(404, "AI 待处理任务不存在");
        return view(rows.get(0));
    }

    public List<Map<String, Object>> list(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.queryForList("SELECT q.*,r.source_name FROM ai_processing_queue q "
                    + "LEFT JOIN source_registry r ON r.id=q.source_registry_id ORDER BY q.created_at DESC,q.id DESC")
                    .stream().map(this::view).toList();
        }
        return jdbc.queryForList("SELECT q.*,r.source_name FROM ai_processing_queue q "
                + "LEFT JOIN source_registry r ON r.id=q.source_registry_id WHERE q.status=? "
                + "ORDER BY q.created_at DESC,q.id DESC", status.trim().toUpperCase())
                .stream().map(this::view).toList();
    }

    @Transactional
    public Map<String, Object> approve(long queueId, AuthUser user) {
        if (!user.isPlatformAdmin()) throw new BusinessException(403, "只有平台管理员可以批准自动 AI 执行");
        Map<String, Object> row = row(queueId);
        if (SUCCEEDED.equals(row.get("status")) || PROCESSING.equals(row.get("status"))) {
            throw new BusinessException(409, "该 AI 任务当前不能批准");
        }
        jdbc.update("UPDATE ai_processing_queue SET status='QUEUED',reason_code='MANUAL_APPROVAL',"
                        + "reason_summary='平台管理员已批准执行',approved_by=?,approved_at=?,available_at=?,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE id=?",
                user.id(), Timestamp.from(now()), Timestamp.from(now()), queueId);
        audit(longOrNull(row.get("source_registry_id")), longOrNull(row.get("crawl_job_id")),
                id(row, "document_id"), queueId, "MANUAL_APPROVAL", null,
                integer(row.get("estimated_tokens")), 0, true, null, null, QUEUED);
        return get(queueId);
    }

    @Transactional
    public Map<String, Integer> reconcile(AuthUser user) {
        if (!user.isPlatformAdmin()) {
            throw new BusinessException(403, "只有平台管理员可以重新评估 AI 队列");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ai_processing_queue WHERE status IN "
                        + "('WAITING_APPROVAL','WAITING_BUDGET','FAILED') "
                        + "ORDER BY created_at,id");
        int requeued = 0;
        int unchanged = 0;
        for (Map<String, Object> row : rows) {
            if (canQueueNow(row)) {
                jdbc.update("UPDATE ai_processing_queue SET status='QUEUED',reason_code='RECONCILED',"
                                + "reason_summary='按当前开关、来源权限和预算重新排队',available_at=?,"
                                + "updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        Timestamp.from(now()), id(row));
                requeued++;
            } else {
                unchanged++;
            }
        }
        return Map.of("requeued", requeued, "unchanged", unchanged);
    }

    @Transactional
    public Map<String, Object> retry(long queueId, AuthUser user) {
        if (!user.isPlatformAdmin()) {
            throw new BusinessException(403, "只有平台管理员可以重试 AI 队列任务");
        }
        Map<String, Object> row = rowForUpdate(queueId);
        if (PROCESSING.equals(row.get("status")) || SUCCEEDED.equals(row.get("status"))
                || DUPLICATE.equals(row.get("status"))) {
            throw new BusinessException(409, "该 AI 任务当前不能重试");
        }
        if (!canQueueNow(row)) {
            throw new BusinessException(409, "当前全局开关或来源权限仍不允许该任务进入自动队列");
        }
        jdbc.update("UPDATE ai_processing_queue SET status='QUEUED',reason_code='MANUAL_RETRY',"
                        + "reason_summary='平台管理员已请求重试',available_at=?,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE id=?",
                Timestamp.from(now()), queueId);
        return get(queueId);
    }

    private boolean canQueueNow(Map<String, Object> row) {
        if (row.get("approved_at") != null) return true;
        if (!autoEnabled) return false;
        Long sourceId = longOrNull(row.get("source_registry_id"));
        if (sourceId == null) return false;
        Integer allowed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_registry WHERE id=? AND enabled=TRUE "
                        + "AND allow_auto_ai=TRUE",
                Integer.class, sourceId);
        return allowed != null && allowed > 0;
    }

    @Transactional
    public Reservation reserveForManual(long documentId, Long processingJobId, AuthUser user) {
        Long sourceId = sourceForDocument(documentId);
        return reserve(null, documentId, sourceId, processingJobId, true,
                user == null ? null : user.id(), null, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserveQueue(long queueId) {
        Map<String, Object> queue = rowForUpdate(queueId);
        if (!QUEUED.equals(queue.get("status")) && !WAITING_BUDGET.equals(queue.get("status"))) {
            return Reservation.blocked(queueId, id(queue, "document_id"),
                    String.valueOf(queue.get("reason_code")), String.valueOf(queue.get("reason_summary")), recovery());
        }
        if (WAITING_BUDGET.equals(queue.get("status")) && queue.get("available_at") instanceof java.util.Date available
                && available.toInstant().isAfter(now())) {
            return Reservation.blocked(queueId, id(queue, "document_id"),
                    String.valueOf(queue.get("reason_code")), String.valueOf(queue.get("reason_summary")), recovery());
        }
        Long taskRootId = longOrNull(queue.get("crawl_task_root_id"));
        return reserve(queueId, id(queue, "document_id"), longOrNull(queue.get("source_registry_id")), null,
                queue.get("approved_at") != null, longOrNull(queue.get("approved_by")), taskRootId, true);
    }

    private Reservation reserve(Long queueId, long documentId, Long sourceId, Long processingJobId,
                                boolean approved, Long approverId, Long taskRootId,
                                boolean applyAutomaticBudgets) {
        Map<String, Object> document = document(documentId);
        String body = text(document.get("raw_text"));
        int estimated = estimateTokens(body);
        if (body.length() > maxInputCharacters) {
            return block(queueId, documentId, sourceId, processingJobId, "INPUT_TOO_LONG",
                    "正文超过单篇最大输入字符 " + maxInputCharacters, "INPUT_CHARACTERS", estimated, approved);
        }
        String hash = contentHash(document, body);
        LocalDate date = LocalDate.now(clock.withZone(zoneId));
        Usage global = null;
        if (applyAutomaticBudgets) {
            ensureUsage(date, "GLOBAL", 0);
            global = usageForUpdate(date, "GLOBAL", 0);
        }
        Integer duplicate = processingJobId == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM ai_budget_reservation WHERE content_hash=? "
                        + "AND status IN ('RESERVED','SETTLED')", Integer.class, hash)
                : jdbc.queryForObject("SELECT COUNT(*) FROM ai_budget_reservation WHERE content_hash=? "
                        + "AND document_id<>? AND status IN ('RESERVED','SETTLED')",
                        Integer.class, hash, documentId);
        if (duplicate != null && duplicate > 0) {
            return block(queueId, documentId, sourceId, processingJobId, "CONTENT_HASH_DUPLICATE",
                    "相同正文 hash 已预留或完成 AI 处理", "DUPLICATE", estimated, approved);
        }
        jdbc.update("DELETE FROM ai_budget_reservation WHERE content_hash=? AND status='FAILED'", hash);
        if (applyAutomaticBudgets && taskRootId != null) {
            Integer taskCount = jdbc.queryForObject("SELECT COUNT(*) FROM ai_budget_reservation "
                            + "WHERE crawl_task_root_id=? AND status IN ('RESERVED','SETTLED')",
                    Integer.class, taskRootId);
            if (taskCount != null && taskCount >= maxArticlesPerTask) {
                return block(queueId, documentId, sourceId, processingJobId, "TASK_ARTICLE_LIMIT",
                        "单任务最大文章数已耗尽", "TASK_ARTICLES", estimated, approved);
            }
        }
        String globalReason = applyAutomaticBudgets
                ? exhausted(global, globalArticleLimit, globalTokenLimit, estimated)
                : null;
        if (globalReason != null) {
            return block(queueId, documentId, sourceId, processingJobId, globalReason,
                    globalReason.endsWith("TOKENS") ? "全局每日 token 预算已耗尽" : "全局每日文章预算已耗尽",
                    globalReason.endsWith("TOKENS") ? "GLOBAL_TOKENS" : "GLOBAL_ARTICLES", estimated, approved);
        }
        Usage source = null;
        int sourceArticles = 0;
        int sourceTokens = 0;
        if (applyAutomaticBudgets && sourceId != null) {
            Map<String, Object> sourceRow = jdbc.queryForMap(
                    "SELECT daily_article_budget,daily_token_budget,allow_auto_ai FROM source_registry WHERE id=?", sourceId);
            if (queueId != null && !approved && !Boolean.TRUE.equals(sourceRow.get("allow_auto_ai"))) {
                return block(queueId, documentId, sourceId, processingJobId, "SOURCE_APPROVAL_REQUIRED",
                        "来源未允许自动 AI，等待人工批准", "APPROVAL", estimated, false);
            }
            sourceArticles = integer(sourceRow.get("daily_article_budget"));
            sourceTokens = integer(sourceRow.get("daily_token_budget"));
            ensureUsage(date, "SOURCE", sourceId);
            source = usageForUpdate(date, "SOURCE", sourceId);
            String sourceReason = exhausted(source, sourceArticles, sourceTokens, estimated);
            if (sourceReason != null) {
                return block(queueId, documentId, sourceId, processingJobId, sourceReason,
                        sourceReason.endsWith("TOKENS") ? "来源每日 token 预算已耗尽" : "来源每日文章预算已耗尽",
                        sourceReason.endsWith("TOKENS") ? "SOURCE_TOKENS" : "SOURCE_ARTICLES", estimated, approved);
            }
        }
        if (applyAutomaticBudgets) {
            jdbc.update("UPDATE ai_budget_usage SET reserved_articles=reserved_articles+1,reserved_tokens=reserved_tokens+?,"
                    + "updated_at=CURRENT_TIMESTAMP WHERE budget_date=? AND scope_type='GLOBAL' AND scope_id=0",
                    estimated, Date.valueOf(date));
        }
        if (source != null) {
            jdbc.update("UPDATE ai_budget_usage SET reserved_articles=reserved_articles+1,reserved_tokens=reserved_tokens+?,"
                    + "updated_at=CURRENT_TIMESTAMP WHERE budget_date=? AND scope_type='SOURCE' AND scope_id=?",
                    estimated, Date.valueOf(date), sourceId);
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_budget_reservation(queue_id,processing_job_id,source_registry_id,crawl_task_root_id,document_id,content_hash,"
                            + "budget_date,estimated_tokens,status,budget_exempt) VALUES (?,?,?,?,?,?,?,?,'RESERVED',?)", new String[] {"id"});
            statement.setObject(1, queueId);
            statement.setObject(2, processingJobId);
            statement.setObject(3, sourceId);
            statement.setObject(4, taskRootId);
            statement.setLong(5, documentId);
            statement.setString(6, hash);
            statement.setDate(7, Date.valueOf(date));
            statement.setInt(8, estimated);
            statement.setBoolean(9, !applyAutomaticBudgets);
            return statement;
        }, keys);
        if (queueId != null) jdbc.update("UPDATE ai_processing_queue SET status='PROCESSING',reason_code=NULL,"
                + "reason_summary=NULL,estimated_tokens=?,attempt_count=attempt_count+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                estimated, queueId);
        Number reservationKey = keys.getKey();
        if (reservationKey == null) throw new IllegalStateException("未取得 AI 预算预留编号");
        return Reservation.allowed(reservationKey.longValue(), queueId, documentId, sourceId, estimated, approved);
    }

    @Transactional
    public void markExecutionStarted(Reservation reservation) {
        if (reservation == null || !reservation.allowed()) return;
        int changed = jdbc.update("UPDATE ai_budget_reservation SET execution_started=TRUE WHERE id=? "
                + "AND status='RESERVED' AND execution_started=FALSE", reservation.reservationId());
        if (changed == 1) {
            audit(reservation.sourceId(), reservation.queueId() == null ? null
                            : longOrNull(row(reservation.queueId()).get("crawl_job_id")), reservation.documentId(),
                    reservation.queueId(), reservation.approved() ? "APPROVED_EXECUTION" : "AUTO_EXECUTION",
                    null, reservation.estimatedTokens(), 0, reservation.approved(), null, null, PROCESSING);
        }
    }

    @Transactional
    public void release(Reservation reservation, String reason) {
        fail(reservation, 0, null, null, reason);
    }

    @Transactional
    public void fail(Reservation reservation, int actualTokens, String provider, String model, String reason) {
        if (reservation == null || !reservation.allowed()) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT execution_started FROM ai_budget_reservation WHERE id=?", reservation.reservationId());
        if (rows.isEmpty()) return;
        boolean callWasStarted = Boolean.TRUE.equals(rows.get(0).get("execution_started"));
        settleInternal(reservation, actualTokens, false, provider, model, callWasStarted, reason);
    }

    @Transactional
    public void settle(Reservation reservation, int actualTokens, boolean success, String provider, String model) {
        settleInternal(reservation, actualTokens, success, provider, model, true,
                success ? "COMPLETED" : "AI_UNAVAILABLE");
    }

    private void settleInternal(Reservation reservation, int actualTokens, boolean success,
                                String provider, String model, boolean crossedBoundary, String reason) {
        if (reservation == null || !reservation.allowed()) return;
        Map<String, Object> stored = jdbc.queryForMap("SELECT * FROM ai_budget_reservation WHERE id=? FOR UPDATE",
                reservation.reservationId());
        if (!"RESERVED".equals(stored.get("status"))) return;
        int estimated = integer(stored.get("estimated_tokens"));
        int actual = Math.max(0, actualTokens);
        LocalDate date = ((Date) stored.get("budget_date")).toLocalDate();
        boolean budgetExempt = Boolean.TRUE.equals(stored.get("budget_exempt"));
        boolean chargeArticle = success;
        if (!budgetExempt) {
            jdbc.update("UPDATE ai_budget_usage SET reserved_articles=reserved_articles-1,reserved_tokens=reserved_tokens-?,"
                            + "settled_articles=settled_articles+?,actual_tokens=actual_tokens+?,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE budget_date=? AND scope_type='GLOBAL' AND scope_id=0",
                    estimated, chargeArticle ? 1 : 0, actual, Date.valueOf(date));
        }
        if (!budgetExempt && reservation.sourceId() != null) {
            jdbc.update("UPDATE ai_budget_usage SET reserved_articles=reserved_articles-1,reserved_tokens=reserved_tokens-?,"
                            + "settled_articles=settled_articles+?,actual_tokens=actual_tokens+?,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE budget_date=? AND scope_type='SOURCE' AND scope_id=?",
                    estimated, chargeArticle ? 1 : 0, actual, Date.valueOf(date), reservation.sourceId());
        }
        jdbc.update("UPDATE ai_budget_reservation SET actual_tokens=?,status=?,settled_at=CURRENT_TIMESTAMP WHERE id=?",
                actual, success ? "SETTLED" : "FAILED", reservation.reservationId());
        if (reservation.queueId() != null) {
            jdbc.update("UPDATE ai_processing_queue SET status=?,actual_tokens=?,reason_code=?,reason_summary=?,"
                            + "provider_id=?,model_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    success ? SUCCEEDED : FAILED, actual, success ? null : safeIdentifier(reason, 60),
                    success ? null : (crossedBoundary
                            ? "AI 服务不可用，正文、版本与事实检查点已保留，可人工重试"
                            : "执行尚未调用 AI，预留已释放，可人工重试"),
                    safeIdentifier(provider, 80), safeIdentifier(model, 120), reservation.queueId());
        }
        if (crossedBoundary) {
            audit(reservation.sourceId(), reservation.queueId() == null ? null
                            : longOrNull(row(reservation.queueId()).get("crawl_job_id")), reservation.documentId(),
                    reservation.queueId(), reason, null, estimated, actual,
                    reservation.approved(), provider, model, success ? SUCCEEDED : FAILED);
        }
    }

    private Reservation block(Long queueId, long documentId, Long sourceId, Long processingJobId,
                              String reason, String summary, String budgetType, int estimated, boolean approved) {
        String status = "APPROVAL".equals(budgetType) ? WAITING_APPROVAL
                : "DUPLICATE".equals(budgetType) ? DUPLICATE : WAITING_BUDGET;
        if (queueId != null) {
            jdbc.update("UPDATE ai_processing_queue SET status=?,reason_code=?,reason_summary=?,estimated_tokens=?,"
                            + "actual_tokens=0,available_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    status, reason, summary, estimated,
                    WAITING_BUDGET.equals(status) ? Timestamp.from(recoveryInstant()) : null, queueId);
        }
        audit(sourceId, null, documentId, queueId, reason, budgetType, estimated, 0,
                approved, null, null, status);
        return Reservation.blocked(queueId, documentId, reason, summary, recovery());
    }

    private void ensureUsage(LocalDate date, String scope, long scopeId) {
        try {
            jdbc.update("INSERT INTO ai_budget_usage(budget_date,scope_type,scope_id) VALUES (?,?,?)",
                    Date.valueOf(date), scope, scopeId);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // The primary key is the concurrency-safe daily reset boundary.
        }
    }

    private Usage usageForUpdate(LocalDate date, String scope, long scopeId) {
        return jdbc.queryForObject("SELECT reserved_articles,settled_articles,reserved_tokens,actual_tokens "
                        + "FROM ai_budget_usage WHERE budget_date=? AND scope_type=? AND scope_id=? FOR UPDATE",
                (rs, row) -> new Usage(rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getLong(4)),
                Date.valueOf(date), scope, scopeId);
    }

    private static String exhausted(Usage usage, int articleLimit, int tokenLimit, int estimated) {
        if (articleLimit > 0 && usage.reservedArticles + usage.settledArticles >= articleLimit) return "BUDGET_ARTICLES";
        if (tokenLimit > 0 && usage.reservedTokens + usage.actualTokens + estimated > tokenLimit) return "BUDGET_TOKENS";
        return null;
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        if (WAITING_BUDGET.equals(row.get("status"))) result.put("estimated_recovery_at", recovery());
        result.remove("provider_request_id");
        return result;
    }

    private Map<String, Object> row(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ai_processing_queue WHERE id=?", id);
        if (rows.isEmpty()) throw new BusinessException(404, "AI 待处理任务不存在");
        return rows.get(0);
    }

    private Map<String, Object> rowForUpdate(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ai_processing_queue WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new BusinessException(404, "AI 待处理任务不存在");
        return rows.get(0);
    }

    private Map<String, Object> document(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,raw_text,content_hash,file_sha256,source_type FROM source_document WHERE id=?", id);
        if (rows.isEmpty()) throw new BusinessException(404, "材料记录不存在");
        return rows.get(0);
    }

    private Long sourceForDocument(long documentId) {
        List<Long> ids = jdbc.query("SELECT j.source_registry_id FROM crawl_job j WHERE j.document_id=? "
                + "ORDER BY j.id DESC LIMIT 1", (rs, row) -> rs.getLong(1), documentId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static String contentHash(Map<String, Object> document, String text) {
        String stored = text(document.get("content_hash"));
        return stored.matches("[0-9a-fA-F]{64}") ? stored.toLowerCase() : sha256(text);
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, (int) Math.ceil(text.length() / 2.0));
    }

    private void audit(Long sourceId, Long crawlJobId, long documentId, Long queueId, String reason,
                       String budgetType, int estimated, int actual, boolean approved,
                       String provider, String model, String result) {
        jdbc.update("INSERT INTO ai_execution_audit(source_registry_id,crawl_job_id,document_id,queue_id,reason_code,"
                        + "budget_type,estimated_tokens,actual_tokens,approved_execution,executed,provider_id,model_id,result) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                sourceId, crawlJobId, documentId, queueId, safeIdentifier(reason, 60),
                safeIdentifier(budgetType, 40), estimated, Math.max(0, actual), approved,
                PROCESSING.equals(result) || SUCCEEDED.equals(result) || FAILED.equals(result),
                safeIdentifier(provider, 80), safeIdentifier(model, 120), safeIdentifier(result, 30));
    }

    private Instant now() { return clock.instant(); }

    private Instant recoveryInstant() {
        ZonedDateTime zoned = ZonedDateTime.ofInstant(now(), zoneId);
        return zoned.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant();
    }

    private String recovery() { return recoveryInstant().toString(); }

    public void setClock(Clock clock) { this.clock = clock; }

    private static String safeIdentifier(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String safe = value.replaceAll("[^A-Za-z0-9._:-]", "_");
        return safe.substring(0, Math.min(max, safe.length()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static int integer(Object value) { return value instanceof Number n ? n.intValue() : 0; }
    private static long id(Map<String, Object> row) { return id(row, "id"); }
    private static long id(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static Long longOrNull(Object value) { return value instanceof Number n ? n.longValue() : null; }

    private record Usage(int reservedArticles, int settledArticles, long reservedTokens, long actualTokens) {}

    public record Reservation(boolean allowed, Long reservationId, Long queueId, long documentId, Long sourceId,
                              int estimatedTokens, boolean approved, String reasonCode, String reasonSummary,
                              String estimatedRecoveryAt) {
        static Reservation allowed(long id, Long queueId, long documentId, Long sourceId, int estimated,
                                   boolean approved) {
            return new Reservation(true, id, queueId, documentId, sourceId, estimated, approved,
                    null, null, null);
        }
        static Reservation blocked(Long queueId, long documentId, String reason, String summary, String recovery) {
            return new Reservation(false, null, queueId, documentId, null, 0, false,
                    reason, summary, recovery);
        }
    }
}
