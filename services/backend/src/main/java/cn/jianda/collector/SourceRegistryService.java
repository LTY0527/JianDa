package cn.jianda.collector;

import cn.jianda.common.BusinessException;
import cn.jianda.security.AuthUser;
import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceRegistryService {
    private static final Set<String> SOURCE_TYPES = Set.of(
            "GOVERNMENT", "PUBLIC_INSTITUTION", "HOSPITAL", "COMMUNITY_HEALTH", "CDC",
            "ELDERLY_SERVICE_ORG", "OFFICIAL_MEDIA", "OFFICIAL_WECHAT",
            "UNIVERSITY_PUBLIC_SERVICE", "OTHER_VERIFIED_OFFICIAL",
            // Historical values remain readable and editable.
            "ANTI_FRAUD", "ELDERLY_CARE", "MAINSTREAM_MEDIA", "OTHER_PUBLIC_SERVICE");
    private static final Set<String> DISCOVERY_MODES = Set.of("MANUAL", "RSS", "ATOM", "SITEMAP", "SECTION", "MIXED");
    private static final Set<String> SCHEDULE_MODES = Set.of("DAILY", "INTERVAL");
    private static final Set<String> DUPLICATE_STRATEGIES = Set.of("SKIP", "CREATE_VERSION");
    private static final Set<String> IMAGE_POLICIES = Set.of(
            "MANUAL_REVIEW", "ORGANIZATION_OWNED", "OFFICIAL_PUBLICITY",
            "AUTHORIZED", "LOCAL_DEMO_CONFIRMED");
    private static final String PUBLIC_COLUMNS = "id,domain,allowed_hosts,source_name,source_type,authority_level,enabled,"
            + "crawl_mode,discovery_mode,homepage_url,rss_url,sitemap_url,section_url,daily_crawl_time,"
            + "max_articles_per_run,allow_image_candidates,allow_auto_ai,daily_article_budget,daily_token_budget,"
            + "schedule_mode,interval_hours,schedule_timezone,recent_days,include_keywords,exclude_keywords,"
            + "auto_save_draft,duplicate_strategy,max_retries,image_usage_policy,image_usage_basis,"
            + "auto_approve_images,image_cache_allowed,image_policy_reviewed_by,image_policy_reviewed_at,"
            + "last_crawled_at,last_status,next_run_at,last_error,failure_count,created_at,updated_at";

    private final JdbcTemplate jdbc;

    public SourceRegistryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT " + PUBLIC_COLUMNS + " FROM source_registry ORDER BY enabled DESC,source_name,id");
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + PUBLIC_COLUMNS + " FROM source_registry WHERE id=?", id);
        if (rows.isEmpty()) throw new BusinessException(404, "权威来源不存在");
        return rows.get(0);
    }

    public void assertPreviewBelongsTo(long sourceId, Map<String, Object> preview) {
        Map<String, Object> source = get(sourceId);
        if (!Boolean.TRUE.equals(source.get("enabled"))) {
            throw new BusinessException(403, "来源尚未启用，不能执行受控采集");
        }
        Object previewSourceId = preview.get("source_registry_id");
        if (!(previewSourceId instanceof Number number) || number.longValue() != sourceId) {
            throw new BusinessException(400, "文章 URL 不属于当前选择的权威来源");
        }
    }

    @Transactional
    public Map<String, Object> create(SourceConfiguration request, AuthUser user) {
        ValidatedSource value = validate(request);
        Integer duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM source_registry WHERE domain=?", Integer.class, value.domain());
        if (duplicate != null && duplicate > 0) throw new BusinessException(409, "该完整域名已存在");
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO source_registry(domain,source_name,source_type,authority_level,enabled,crawl_mode,discovery_mode,"
                            + "homepage_url,rss_url,sitemap_url,section_url,daily_crawl_time,max_articles_per_run,"
                            + "allow_image_cache,allow_image_candidates,allow_auto_crawl,allow_auto_ai,daily_article_budget,daily_token_budget,"
                            + "requires_manual_review,last_status,operator_id,updated_at) "
                            + "VALUES (?,?,?,? ,FALSE,'MANUAL',?,?,?,?,?,?,?, FALSE,?,FALSE,?,?,?, TRUE,'NEVER_RUN',?,CURRENT_TIMESTAMP)",
                    new String[] {"id"});
            bind(statement, value, user.id());
            return statement;
        }, keys);
        Number generatedKey = keys.getKey();
        if (generatedKey == null) throw new IllegalStateException("未能取得来源编号");
        long id = generatedKey.longValue();
        updateAdvanced(id, value, user.id());
        log(user, "CREATE_SOURCE_REGISTRY", id);
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(long id, SourceConfiguration request, AuthUser user) {
        get(id);
        ValidatedSource value = validate(request);
        Integer duplicate = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_registry WHERE domain=? AND id<>?", Integer.class, value.domain(), id);
        if (duplicate != null && duplicate > 0) throw new BusinessException(409, "该完整域名已存在");
        int changed = jdbc.update("UPDATE source_registry SET domain=?,allowed_hosts=?,source_name=?,source_type=?,authority_level=?,"
                        + "discovery_mode=?,homepage_url=?,rss_url=?,sitemap_url=?,section_url=?,daily_crawl_time=?,"
                        + "max_articles_per_run=?,allow_image_candidates=?,allow_auto_ai=?,daily_article_budget=?,daily_token_budget=?,"
                        + "schedule_mode=?,interval_hours=?,schedule_timezone=?,recent_days=?,include_keywords=?,exclude_keywords=?,"
                        + "auto_save_draft=?,duplicate_strategy=?,max_retries=?,image_usage_policy=?,image_usage_basis=?,"
                        + "auto_approve_images=?,image_cache_allowed=?,allow_image_cache=?,image_policy_reviewed_by=?,image_policy_reviewed_at=?,"
                        + "operator_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                value.domain(), value.allowedHosts(), value.name(), value.type(), value.authorityLevel(), value.discoveryMode(),
                value.homepageUrl(), value.rssUrl(), value.sitemapUrl(), value.sectionUrl(), value.dailyCrawlTime(),
                value.maxArticlesPerRun(), value.allowImageCandidates(), value.allowAutoAi(), value.dailyArticleBudget(),
                value.dailyTokenBudget(), value.scheduleMode(), value.intervalHours(), value.scheduleTimezone(),
                value.recentDays(), value.includeKeywords(), value.excludeKeywords(), value.autoSaveDraft(),
                value.duplicateStrategy(), value.maxRetries(), value.imageUsagePolicy(), value.imageUsageBasis(),
                value.autoApproveImages(), value.imageCacheAllowed(), value.imageCacheAllowed(),
                value.autoApproveImages() ? user.id() : null,
                value.autoApproveImages() ? Timestamp.valueOf(LocalDateTime.now()) : null,
                user.id(), id);
        if (changed == 0) throw new BusinessException(404, "权威来源不存在");
        log(user, "UPDATE_SOURCE_REGISTRY", id);
        return get(id);
    }

    @Transactional
    public Map<String, Object> setEnabled(long id, boolean enabled, AuthUser user) {
        int changed = jdbc.update("UPDATE source_registry SET enabled=?,paused_at=?,next_run_at=?,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE id=?",
                enabled, enabled ? null : Timestamp.valueOf(LocalDateTime.now()),
                enabled ? Timestamp.valueOf(LocalDateTime.now()) : null, id);
        if (changed == 0) throw new BusinessException(404, "权威来源不存在");
        log(user, enabled ? "ENABLE_SOURCE_REGISTRY" : "DISABLE_SOURCE_REGISTRY", id);
        return get(id);
    }

    @Transactional
    public Map<String, Object> setImageCandidatesEnabled(long id, boolean enabled, AuthUser user) {
        int changed = jdbc.update(
                "UPDATE source_registry SET allow_image_candidates=?,operator_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                enabled, user.id(), id);
        if (changed == 0) throw new BusinessException(404, "权威来源不存在");
        log(user, enabled ? "ENABLE_IMAGE_CANDIDATES" : "DISABLE_IMAGE_CANDIDATES", id);
        return get(id);
    }

    @Transactional
    public Map<String, Object> confirmQuickSource(
            QuickSourceConfirmation request, Map<String, Object> preview, AuthUser user) {
        if (request == null || !request.officialConfirmed()) {
            throw new BusinessException(400, "必须由平台管理员确认该来源属于官方机构");
        }
        String note = optionalText(request.verificationNote(), 1000);
        if (note == null) throw new BusinessException(400, "请填写官方性质核对说明");
        String fingerprint = String.valueOf(preview.getOrDefault("source_identity_fingerprint", "")).trim();
        if (!fingerprint.matches("[0-9a-f]{64}")) throw new BusinessException(400, "来源身份指纹无效");
        String domain = String.valueOf(preview.getOrDefault("domain", "")).trim().toLowerCase(Locale.ROOT);
        String canonical = String.valueOf(preview.getOrDefault("canonical_url", "")).trim();
        String sourceType = defaultNormalized(request.sourceType(),
                Boolean.TRUE.equals(preview.get("wechat_article")) ? "OFFICIAL_WECHAT" : "OTHER_VERIFIED_OFFICIAL");
        if (!SOURCE_TYPES.contains(sourceType)) throw new BusinessException(400, "来源类型不正确");
        if ("OFFICIAL_WECHAT".equals(sourceType)
                && text(preview.get("wechat_account_name")).isBlank()
                && text(preview.get("wechat_biz")).isBlank()) {
            throw new BusinessException(400, "未提取到可核对的公众号名称或账号标识");
        }
        String mode = defaultNormalized(request.mode(), "SAVE_TRUSTED");
        if (!Set.of("TEMPORARY_IMPORT", "SAVE_TRUSTED", "SAVE_MANUAL_SCAN", "SAVE_AUTO_SCAN").contains(mode)) {
            throw new BusinessException(400, "快速确认方式不正确");
        }
        List<Long> existing = jdbc.query("SELECT id FROM source_registry WHERE LOWER(domain)=? ORDER BY id LIMIT 1",
                (row, index) -> row.getLong(1), domain);
        long registryId;
        if (existing.isEmpty()) {
            SourceConfiguration configuration = new SourceConfiguration(
                    request.sourceName() == null || request.sourceName().isBlank()
                            ? fallbackSourceName(preview) : request.sourceName().trim(),
                    domain, sourceType, "A", canonical, "", "", canonical, "MANUAL", "03:30", 10,
                    true, false, 20, 100000, "DAILY", 24, "Asia/Shanghai", 7, "", "",
                    true, "SKIP", 3, defaultNormalized(request.imageUsagePolicy(), "MANUAL_REVIEW"),
                    request.imageUsageBasis(), Boolean.TRUE.equals(request.autoApproveImages()),
                    Boolean.TRUE.equals(request.imageCacheAllowed()), "");
            registryId = ((Number) create(configuration, user).get("id")).longValue();
        } else {
            registryId = existing.get(0);
        }
        boolean enabled = !"TEMPORARY_IMPORT".equals(mode);
        boolean autoCrawl = "SAVE_AUTO_SCAN".equals(mode);
        jdbc.update("UPDATE source_registry SET enabled=?,allow_auto_crawl=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                enabled, autoCrawl, registryId);
        List<Long> identityIds = jdbc.query(
                "SELECT id FROM source_registry_identity WHERE source_identity_fingerprint=?",
                (row, index) -> row.getLong(1), fingerprint);
        if (identityIds.isEmpty()) {
            jdbc.update("INSERT INTO source_registry_identity(source_registry_id,identity_type,wechat_account_name,"
                            + "account_subject,wechat_biz,verification_note,official_verified,verified_by,verified_at,"
                            + "source_identity_fingerprint) VALUES (?,?,?,?,?,?,TRUE,?,CURRENT_TIMESTAMP,?)",
                    registryId, Boolean.TRUE.equals(preview.get("wechat_article")) ? "WECHAT_ACCOUNT" : "WEB_DOMAIN",
                    nullableText(preview.get("wechat_account_name")), nullableText(preview.get("account_subject")),
                    nullableText(preview.get("wechat_biz")), note, user.id(), fingerprint);
        } else {
            jdbc.update("UPDATE source_registry_identity SET verification_note=?,official_verified=TRUE,"
                            + "verified_by=?,verified_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    note, user.id(), identityIds.get(0));
        }
        log(user, "VERIFY_SOURCE_IDENTITY", registryId);
        Map<String, Object> result = new java.util.LinkedHashMap<>(get(registryId));
        result.put("confirmation_mode", mode);
        result.put("source_identity_fingerprint", fingerprint);
        result.put("official_verified", true);
        return result;
    }

    public boolean acquireLease(long sourceId, String owner, Duration duration) {
        validateOwner(owner);
        if (duration == null || duration.isZero() || duration.isNegative() || duration.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("租约时长必须在 1 秒到 1 小时之间");
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        Timestamp until = Timestamp.valueOf(LocalDateTime.now().plus(duration));
        return jdbc.update("UPDATE source_registry SET lock_owner=?,lock_until=?,last_status='RUNNING',updated_at=CURRENT_TIMESTAMP "
                        + "WHERE id=? AND enabled=TRUE AND (lock_until IS NULL OR lock_until<? OR lock_owner=?)",
                owner.trim(), until, sourceId, now, owner.trim()) == 1;
    }

    public boolean releaseLease(long sourceId, String owner, String status, String errorSummary) {
        validateOwner(owner);
        String safeStatus = Set.of("SUCCESS", "FAILED", "PARTIAL_SUCCESS", "CANCELLED").contains(status)
                ? status : "FAILED";
        String safeError = errorSummary == null ? null : errorSummary.strip();
        if (safeError != null && safeError.length() > 500) safeError = safeError.substring(0, 500);
        return jdbc.update("UPDATE source_registry SET lock_owner=NULL,lock_until=NULL,last_status=?,last_error=?,"
                        + "last_crawled_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND lock_owner=?",
                safeStatus, safeError, sourceId, owner.trim()) == 1;
    }

    private static void validateOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 100) {
            throw new IllegalArgumentException("锁持有者不能为空且最多 100 个字符");
        }
    }

    private static ValidatedSource validate(SourceConfiguration request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException(400, "来源名称不能为空");
        }
        String type = normalized(request.type());
        if (!SOURCE_TYPES.contains(type)) throw new BusinessException(400, "来源类型不正确");
        String discoveryMode = normalized(request.discoveryMode());
        if (!DISCOVERY_MODES.contains(discoveryMode)) throw new BusinessException(400, "发现方式不正确");
        URI homepage = httpUri(request.homepageUrl(), "主页地址");
        String domain = request.domain() == null || request.domain().isBlank()
                ? homepage.getHost().toLowerCase(Locale.ROOT) : request.domain().trim().toLowerCase(Locale.ROOT);
        if (!domain.equals(homepage.getHost().toLowerCase(Locale.ROOT)) || domain.contains("/") || domain.contains(":")) {
            throw new BusinessException(400, "完整域名必须与主页地址一致且不能包含端口或路径");
        }
        String rss = optionalUrl(request.rssUrl(), domain, "RSS/Atom 地址");
        String sitemap = optionalUrl(request.sitemapUrl(), domain, "Sitemap 地址");
        String section = optionalUrl(request.sectionUrl(), domain, "栏目页地址");
        int maxArticles = request.maxArticlesPerRun() == null ? 5 : request.maxArticlesPerRun();
        int articleBudget = request.dailyArticleBudget() == null ? 0 : request.dailyArticleBudget();
        int tokenBudget = request.dailyTokenBudget() == null ? 0 : request.dailyTokenBudget();
        if (maxArticles < 1 || maxArticles > 100) throw new BusinessException(400, "每轮文章上限必须在 1 到 100 之间");
        if (articleBudget < 0 || articleBudget > 10000 || tokenBudget < 0 || tokenBudget > 100000000) {
            throw new BusinessException(400, "每日预算超出允许范围");
        }
        String crawlTime = request.dailyCrawlTime() == null || request.dailyCrawlTime().isBlank()
                ? "03:30" : request.dailyCrawlTime().trim();
        if (!crawlTime.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) throw new BusinessException(400, "采集时间格式应为 HH:mm");
        String authority = request.authorityLevel() == null || request.authorityLevel().isBlank()
                ? "B" : request.authorityLevel().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("A", "B", "C").contains(authority)) throw new BusinessException(400, "权威等级不正确");
        String scheduleMode = defaultNormalized(request.scheduleMode(), "DAILY");
        if (!SCHEDULE_MODES.contains(scheduleMode)) throw new BusinessException(400, "调度方式不正确");
        int intervalHours = request.intervalHours() == null ? 24 : request.intervalHours();
        int recentDays = request.recentDays() == null ? 7 : request.recentDays();
        int maxRetries = request.maxRetries() == null ? 3 : request.maxRetries();
        if (intervalHours < 1 || intervalHours > 168) throw new BusinessException(400, "间隔小时必须在 1 到 168 之间");
        if (!Set.of(1, 3, 7, 30).contains(recentDays)) throw new BusinessException(400, "最近天数仅支持 1、3、7 或 30");
        if (maxRetries < 0 || maxRetries > 10) throw new BusinessException(400, "失败重试次数必须在 0 到 10 之间");
        String timezone = request.scheduleTimezone() == null || request.scheduleTimezone().isBlank()
                ? "Asia/Shanghai" : request.scheduleTimezone().trim();
        try {
            java.time.ZoneId.of(timezone);
        } catch (java.time.DateTimeException exception) {
            throw new BusinessException(400, "时区名称不正确");
        }
        String duplicateStrategy = defaultNormalized(request.duplicateStrategy(), "SKIP");
        if (!DUPLICATE_STRATEGIES.contains(duplicateStrategy)) throw new BusinessException(400, "重复内容策略不正确");
        String imagePolicy = defaultNormalized(request.imageUsagePolicy(), "MANUAL_REVIEW");
        if (!IMAGE_POLICIES.contains(imagePolicy)) throw new BusinessException(400, "图片使用策略不正确");
        boolean autoApproveImages = Boolean.TRUE.equals(request.autoApproveImages());
        boolean imageCacheAllowed = Boolean.TRUE.equals(request.imageCacheAllowed());
        String imageBasis = optionalText(request.imageUsageBasis(), 1000);
        if ((autoApproveImages || imageCacheAllowed) && ("MANUAL_REVIEW".equals(imagePolicy) || imageBasis == null)) {
            throw new BusinessException(400, "自动确认或缓存图片前必须选择明确策略并填写使用依据");
        }
        String allowedHosts = normalizeAllowedHosts(request.allowedHosts(), domain);
        return new ValidatedSource(request.name().trim(), domain, type, authority, homepage.toString(), rss, sitemap,
                section, discoveryMode, crawlTime, maxArticles, Boolean.TRUE.equals(request.allowImageCandidates()),
                Boolean.TRUE.equals(request.allowAutoAi()), articleBudget, tokenBudget, scheduleMode, intervalHours,
                timezone, recentDays, optionalText(request.includeKeywords(), 1000),
                optionalText(request.excludeKeywords(), 1000), !Boolean.FALSE.equals(request.autoSaveDraft()),
                duplicateStrategy, maxRetries, imagePolicy, imageBasis, autoApproveImages, imageCacheAllowed,
                allowedHosts);
    }

    private static URI httpUri(String value, String label) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
            return uri.normalize();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, label + "必须是有效的 HTTP 或 HTTPS 地址");
        }
    }

    private static String optionalUrl(String value, String domain, String label) {
        if (value == null || value.isBlank()) return null;
        URI uri = httpUri(value, label);
        if (!domain.equalsIgnoreCase(uri.getHost())) throw new BusinessException(400, label + "必须与来源完整域名相同");
        return uri.toString();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String defaultNormalized(String value, String fallback) {
        String normalized = normalized(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static String normalizeAllowedHosts(String value, String primaryDomain) {
        if (value == null || value.isBlank()) return null;
        List<String> hosts = java.util.Arrays.stream(value.split("[,，;；\\s]+"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .filter(item -> !item.equals(primaryDomain))
                .filter(item -> item.matches("(?i)^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$"))
                .distinct()
                .limit(20)
                .toList();
        return hosts.isEmpty() ? null : String.join(",", hosts);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String nullableText(Object value) {
        String result = text(value);
        return result.isBlank() ? null : result;
    }

    private static String fallbackSourceName(Map<String, Object> preview) {
        for (String key : List.of("wechat_account_name", "source_name", "domain")) {
            String value = text(preview.get(key));
            if (!value.isBlank()) return value;
        }
        return "待核对官方来源";
    }

    private void updateAdvanced(long id, ValidatedSource value, long operatorId) {
        jdbc.update("UPDATE source_registry SET schedule_mode=?,interval_hours=?,schedule_timezone=?,recent_days=?,"
                        + "include_keywords=?,exclude_keywords=?,auto_save_draft=?,duplicate_strategy=?,max_retries=?,"
                        + "image_usage_policy=?,image_usage_basis=?,auto_approve_images=?,image_cache_allowed=?,"
                        + "allow_image_cache=?,image_policy_reviewed_by=?,image_policy_reviewed_at=?,allowed_hosts=? WHERE id=?",
                value.scheduleMode(), value.intervalHours(), value.scheduleTimezone(), value.recentDays(),
                value.includeKeywords(), value.excludeKeywords(), value.autoSaveDraft(), value.duplicateStrategy(),
                value.maxRetries(), value.imageUsagePolicy(), value.imageUsageBasis(), value.autoApproveImages(),
                value.imageCacheAllowed(), value.imageCacheAllowed(), value.autoApproveImages() ? operatorId : null,
                value.autoApproveImages() ? Timestamp.valueOf(LocalDateTime.now()) : null,
                value.allowedHosts(), id);
    }

    private static void bind(PreparedStatement statement, ValidatedSource value, long operatorId) throws java.sql.SQLException {
        statement.setString(1, value.domain());
        statement.setString(2, value.name());
        statement.setString(3, value.type());
        statement.setString(4, value.authorityLevel());
        statement.setString(5, value.discoveryMode());
        statement.setString(6, value.homepageUrl());
        statement.setString(7, value.rssUrl());
        statement.setString(8, value.sitemapUrl());
        statement.setString(9, value.sectionUrl());
        statement.setString(10, value.dailyCrawlTime());
        statement.setInt(11, value.maxArticlesPerRun());
        statement.setBoolean(12, value.allowImageCandidates());
        statement.setBoolean(13, value.allowAutoAi());
        statement.setInt(14, value.dailyArticleBudget());
        statement.setInt(15, value.dailyTokenBudget());
        statement.setLong(16, operatorId);
    }

    private void log(AuthUser user, String action, long id) {
        jdbc.update("INSERT INTO operation_log(operator_id,organization_id,action,target_type,target_id,result,ip) "
                        + "VALUES (?,?,?,'SOURCE_REGISTRY',?,'SUCCESS','local')",
                user.id(), user.organizationId(), action, id);
    }

    public record SourceConfiguration(String name, String domain, String type, String authorityLevel,
            String homepageUrl, String rssUrl, String sitemapUrl, String sectionUrl, String discoveryMode,
            String dailyCrawlTime, Integer maxArticlesPerRun, Boolean allowImageCandidates, Boolean allowAutoAi,
            Integer dailyArticleBudget, Integer dailyTokenBudget, String scheduleMode, Integer intervalHours,
            String scheduleTimezone, Integer recentDays, String includeKeywords, String excludeKeywords,
            Boolean autoSaveDraft, String duplicateStrategy, Integer maxRetries, String imageUsagePolicy,
            String imageUsageBasis, Boolean autoApproveImages, Boolean imageCacheAllowed,
            String allowedHosts) {}

    public record QuickSourceConfirmation(String sourceName, String sourceType, String verificationNote,
            boolean officialConfirmed, String mode, String imageUsagePolicy, String imageUsageBasis,
            Boolean autoApproveImages, Boolean imageCacheAllowed, Boolean continueImport) {}

    private record ValidatedSource(String name, String domain, String type, String authorityLevel,
            String homepageUrl, String rssUrl, String sitemapUrl, String sectionUrl, String discoveryMode,
            String dailyCrawlTime, int maxArticlesPerRun, boolean allowImageCandidates, boolean allowAutoAi,
            int dailyArticleBudget, int dailyTokenBudget, String scheduleMode, int intervalHours,
            String scheduleTimezone, int recentDays, String includeKeywords, String excludeKeywords,
            boolean autoSaveDraft, String duplicateStrategy, int maxRetries, String imageUsagePolicy,
            String imageUsageBasis, boolean autoApproveImages, boolean imageCacheAllowed,
            String allowedHosts) {}
}
