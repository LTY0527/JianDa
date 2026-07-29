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
            "GOVERNMENT", "HOSPITAL", "COMMUNITY_HEALTH", "ANTI_FRAUD",
            "ELDERLY_CARE", "MAINSTREAM_MEDIA", "PUBLIC_INSTITUTION", "OTHER_PUBLIC_SERVICE");
    private static final Set<String> DISCOVERY_MODES = Set.of("MANUAL", "RSS", "ATOM", "SITEMAP", "SECTION", "MIXED");
    private static final String PUBLIC_COLUMNS = "id,domain,source_name,source_type,authority_level,enabled,"
            + "crawl_mode,discovery_mode,homepage_url,rss_url,sitemap_url,section_url,daily_crawl_time,"
            + "max_articles_per_run,allow_image_candidates,allow_auto_ai,daily_article_budget,daily_token_budget,"
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
        int changed = jdbc.update("UPDATE source_registry SET domain=?,source_name=?,source_type=?,authority_level=?,"
                        + "discovery_mode=?,homepage_url=?,rss_url=?,sitemap_url=?,section_url=?,daily_crawl_time=?,"
                        + "max_articles_per_run=?,allow_image_candidates=?,allow_auto_ai=?,daily_article_budget=?,daily_token_budget=?,"
                        + "operator_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                value.domain(), value.name(), value.type(), value.authorityLevel(), value.discoveryMode(),
                value.homepageUrl(), value.rssUrl(), value.sitemapUrl(), value.sectionUrl(), value.dailyCrawlTime(),
                value.maxArticlesPerRun(), value.allowImageCandidates(), value.allowAutoAi(), value.dailyArticleBudget(),
                value.dailyTokenBudget(), user.id(), id);
        if (changed == 0) throw new BusinessException(404, "权威来源不存在");
        log(user, "UPDATE_SOURCE_REGISTRY", id);
        return get(id);
    }

    @Transactional
    public Map<String, Object> setEnabled(long id, boolean enabled, AuthUser user) {
        int changed = jdbc.update("UPDATE source_registry SET enabled=?,allow_auto_crawl=?,paused_at=?,next_run_at=?,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE id=?",
                enabled, enabled, enabled ? null : Timestamp.valueOf(LocalDateTime.now()),
                enabled ? Timestamp.valueOf(LocalDateTime.now()) : null, id);
        if (changed == 0) throw new BusinessException(404, "权威来源不存在");
        log(user, enabled ? "ENABLE_SOURCE_REGISTRY" : "DISABLE_SOURCE_REGISTRY", id);
        return get(id);
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
        return new ValidatedSource(request.name().trim(), domain, type, authority, homepage.toString(), rss, sitemap,
                section, discoveryMode, crawlTime, maxArticles, Boolean.TRUE.equals(request.allowImageCandidates()),
                Boolean.TRUE.equals(request.allowAutoAi()), articleBudget, tokenBudget);
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
            Integer dailyArticleBudget, Integer dailyTokenBudget) {}

    private record ValidatedSource(String name, String domain, String type, String authorityLevel,
            String homepageUrl, String rssUrl, String sitemapUrl, String sectionUrl, String discoveryMode,
            String dailyCrawlTime, int maxArticlesPerRun, boolean allowImageCandidates, boolean allowAutoAi,
            int dailyArticleBudget, int dailyTokenBudget) {}
}
