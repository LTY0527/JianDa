package cn.jianda.collector;

import cn.jianda.common.BusinessException;
import cn.jianda.security.AuthUser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentImportService {
    private static final Set<String> SOURCE_TYPES = Set.of("GOVERNMENT", "HOSPITAL", "MAINSTREAM_MEDIA", "PUBLIC_INSTITUTION");
    private final JdbcTemplate jdbc;
    private final ManualImportCollector manualCollector;
    private final FixtureCollector fixtureCollector;

    public ContentImportService(JdbcTemplate jdbc, ManualImportCollector manualCollector, FixtureCollector fixtureCollector) {
        this.jdbc = jdbc;
        this.manualCollector = manualCollector;
        this.fixtureCollector = fixtureCollector;
    }

    public List<Map<String, Object>> listSources() {
        return jdbc.queryForList("SELECT id,source_name,source_type,source_url,publisher,whitelist_status,enabled,last_imported_at,notes,imported_at created_at "
                + "FROM content_source ORDER BY enabled DESC,source_name");
    }

    @Transactional
    public Map<String, Object> createSource(String name, String type, String url, String publisher, String notes, AuthUser user) {
        String normalizedType = type == null ? "" : type.trim().toUpperCase();
        if (!SOURCE_TYPES.contains(normalizedType)) {
            throw new BusinessException(400, "来源类型不正确");
        }
        validateHttpUrl(url);
        Integer duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM content_source WHERE source_url=?", Integer.class, url.trim());
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException(409, "该来源 URL 已存在");
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO content_source(organization_id,source_type,source_name,source_url,publisher,status,whitelist_status,enabled,notes,created_by) "
                            + "VALUES (NULL,?,?,?,?,'ACTIVE','APPROVED',TRUE,?,?)", new String[] {"id"});
            statement.setString(1, normalizedType);
            statement.setString(2, name.trim());
            statement.setString(3, url.trim());
            statement.setString(4, publisher == null ? name.trim() : publisher.trim());
            statement.setString(5, notes == null ? null : notes.trim());
            statement.setLong(6, user.id());
            return statement;
        }, keys);
        long id = keys.getKey().longValue();
        log(user, "CREATE_PUBLIC_SOURCE", "CONTENT_SOURCE", id, "SUCCESS");
        return jdbc.queryForMap("SELECT * FROM content_source WHERE id=?", id);
    }

    @Transactional
    public void setEnabled(long id, boolean enabled, AuthUser user) {
        int changed = jdbc.update("UPDATE content_source SET enabled=?,status=? WHERE id=?", enabled, enabled ? "ACTIVE" : "DISABLED", id);
        if (changed == 0) {
            throw new BusinessException(404, "权威来源不存在");
        }
        log(user, enabled ? "ENABLE_PUBLIC_SOURCE" : "DISABLE_PUBLIC_SOURCE", "CONTENT_SOURCE", id, "SUCCESS");
    }

    public List<CollectedContent> listFixtures() {
        return fixtureCollector.available();
    }

    @Transactional
    public Map<String, Object> importManual(long sourceId, CollectionRequest request, AuthUser user) {
        return importCollected(sourceId, manualCollector.collect(request), manualCollector.type(), user);
    }

    @Transactional
    public Map<String, Object> importFixture(String fixtureId, AuthUser user) {
        CollectedContent content = fixtureCollector.collect(new CollectionRequest(fixtureId, null, null, null,
                null, null, null, null, null));
        List<Map<String, Object>> sources = jdbc.queryForList(
                "SELECT * FROM content_source WHERE source_name=? ORDER BY id LIMIT 1", content.sourceName());
        if (sources.isEmpty()) {
            throw new BusinessException(400, "请先在权威来源管理中添加并启用“" + content.sourceName() + "”");
        }
        long sourceId = ((Number) sources.get(0).get("id")).longValue();
        return importCollected(sourceId, content, fixtureCollector.type(), user);
    }

    public List<Map<String, Object>> listImports() {
        return jdbc.queryForList("SELECT d.id,d.title,d.processing_status status,d.category,d.import_method,d.import_url,d.source_published_at,d.imported_at,d.updated_at,"
                + "s.source_name,s.source_type,s.publisher,s.source_url,"
                + "(SELECT j.error_message FROM processing_job j WHERE j.document_id=d.id ORDER BY j.id DESC LIMIT 1) failure_reason "
                + "FROM source_document d JOIN content_source s ON s.id=d.content_source_id "
                + "ORDER BY d.imported_at DESC,d.id DESC");
    }

    public Map<String, Object> preview(long documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT d.*,s.source_name,s.source_type,s.publisher,s.source_url whitelist_url "
                + "FROM source_document d JOIN content_source s ON s.id=d.content_source_id WHERE d.id=?", documentId);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "导入记录不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> importCollected(long sourceId, CollectedContent content, String method, AuthUser user) {
        Map<String, Object> source = source(sourceId);
        validateSource(source, content.sourceUrl());
        String contentHash = sha256(content.body().replaceAll("\\s+", "").trim());
        Integer duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM source_document WHERE import_url=? OR content_hash=?",
                Integer.class, content.sourceUrl(), contentHash);
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException(409, "该 URL 或相同正文已经导入，请勿重复操作");
        }

        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO source_document(organization_id,content_source_id,title,raw_text,page_count,processing_status,created_by,import_url,source_published_at,imported_at,content_hash,category,import_method) "
                            + "VALUES (?,?,?, ?,1,'UPLOADED',?,?,?,CURRENT_TIMESTAMP,?,?,?)", new String[] {"id"});
            statement.setLong(1, user.organizationId());
            statement.setLong(2, sourceId);
            statement.setString(3, content.title());
            statement.setString(4, content.body());
            statement.setLong(5, user.id());
            statement.setString(6, content.sourceUrl());
            statement.setObject(7, content.publishedAt());
            statement.setString(8, contentHash);
            statement.setString(9, content.category());
            statement.setString(10, method);
            return statement;
        }, keys);
        long documentId = keys.getKey().longValue();
        jdbc.update("UPDATE content_source SET last_imported_at=CURRENT_TIMESTAMP,imported_at=CURRENT_TIMESTAMP WHERE id=?", sourceId);
        log(user, "IMPORT_PUBLIC_CONTENT", "SOURCE_DOCUMENT", documentId, "SUCCESS");
        return Map.of("documentId", documentId, "status", "UPLOADED", "title", content.title(), "method", method);
    }

    private Map<String, Object> source(long sourceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM content_source WHERE id=?", sourceId);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "权威来源不存在");
        }
        return rows.get(0);
    }

    private static void validateSource(Map<String, Object> source, String contentUrl) {
        boolean enabled = Boolean.TRUE.equals(source.get("enabled")) || "true".equalsIgnoreCase(String.valueOf(source.get("enabled")));
        if (!enabled || !"APPROVED".equals(source.get("whitelist_status"))) {
            throw new BusinessException(403, "该来源未通过白名单或已停用");
        }
        validateHttpUrl(contentUrl);
        URI whitelist = URI.create(source.get("source_url").toString());
        URI candidate = URI.create(contentUrl);
        if (whitelist.getHost() == null || candidate.getHost() == null
                || !whitelist.getHost().equalsIgnoreCase(candidate.getHost())) {
            throw new BusinessException(403, "来源 URL 不在已批准的域名白名单中");
        }
    }

    private static void validateHttpUrl(String url) {
        try {
            URI uri = URI.create(url == null ? "" : url.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "请输入有效的 HTTP 或 HTTPS 来源 URL");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private void log(AuthUser user, String action, String targetType, long targetId, String result) {
        jdbc.update("INSERT INTO operation_log(operator_id,organization_id,action,target_type,target_id,result,ip) VALUES (?,?,?,?,?,?,'local')",
                user.id(), user.organizationId(), action, targetType, targetId, result);
    }
}
