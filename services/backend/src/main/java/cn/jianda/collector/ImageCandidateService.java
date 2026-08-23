package cn.jianda.collector;

import cn.jianda.ai.AiClient;
import cn.jianda.common.BusinessException;
import cn.jianda.security.AuthUser;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImageCandidateService {
    private final JdbcTemplate jdbc;
    private final AiClient aiClient;
    private final Path uploadRoot;

    public ImageCandidateService(
            JdbcTemplate jdbc, AiClient aiClient, @Value("${jianda.upload-dir}") String uploadDir) {
        this.jdbc = jdbc;
        this.aiClient = aiClient;
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    public List<Map<String, Object>> list(long documentId, AuthUser user) {
        assertAccess(documentId, user);
        return jdbc.queryForList("SELECT id,document_id,candidate_url,source_page_url,source_name,alt_text,width,height,"
                + "mime_type,image_hash,image_cached,discovery_method,priority_rank,candidate_status,rights_status,"
                + "review_status,rejection_reason,usage_basis,context_text,relevance_score,created_at,updated_at,reviewed_at,reviewer_id "
                + "FROM image_candidate WHERE document_id=? ORDER BY relevance_score DESC,priority_rank,id", documentId);
    }

    @Transactional
    public void persist(long documentId, String sourcePage, Object rawImages) {
        jdbc.update("DELETE FROM image_candidate WHERE document_id=? AND review_status='PENDING'", documentId);
        if (!(rawImages instanceof List<?> images)) return;
        int rank = 1;
        Set<String> seen = new java.util.HashSet<>();
        for (Object value : images) {
            if (!(value instanceof Map<?, ?> image)) continue;
            String url = text(image.get("url"));
            if (url.isBlank() || !seen.add(url) || !safeHttpUrl(url)) continue;
            String status = text(image.get("candidate_status"));
            if (!status.isBlank() && !"VALID".equals(status)) continue;
            Integer width = number(image.get("width"));
            Integer height = number(image.get("height"));
            if (width == null || height == null || width < 600 || height < 250) continue;
            double ratio = (double) width / height;
            if (ratio < 0.75 || ratio > 2.4) continue;
            String fingerprint = (url + " " + text(image.get("caption"))).toLowerCase(Locale.ROOT);
            if (List.of("logo", "icon", "avatar", "qrcode", "qr-code", "tracking", "pixel", "advert", "广告", "二维码", "头像", "图标")
                    .stream().anyMatch(fingerprint::contains)) continue;
            jdbc.update("INSERT INTO image_candidate(document_id,candidate_url,source_page_url,source_name,alt_text,width,height,"
                            + "mime_type,image_hash,image_cached,discovery_method,priority_rank,context_text,relevance_score,"
                            + "candidate_status,rights_status,review_status) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'VALID','UNKNOWN','PENDING')",
                    documentId, url, sourcePage, "原网页", text(image.get("caption")), width, height,
                    nullable(image.get("mime_type")), nullable(image.get("image_hash")),
                    Boolean.TRUE.equals(image.get("image_cached")), method(image.get("discovery_method")), rank++,
                    truncate(text(image.get("context_text")), 1000), relevance(image.get("relevance_score")));
        }
    }

    @Transactional
    public void approve(long candidateId, String sourceName, String usageBasis, AuthUser user) {
        Map<String, Object> candidate = candidate(candidateId);
        long documentId = ((Number) candidate.get("document_id")).longValue();
        assertAccess(documentId, user);
        if (sourceName == null || sourceName.isBlank() || usageBasis == null || usageBasis.isBlank()) {
            throw new BusinessException(400, "确认图片时必须填写图片来源和许可说明");
        }
        jdbc.update("UPDATE image_candidate SET source_name=?,usage_basis=?,rights_status='CONFIRMED',review_status='APPROVED',"
                        + "rejection_reason=NULL,reviewed_at=CURRENT_TIMESTAMP,reviewer_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                sourceName.trim(), usageBasis.trim(), user.id(), candidateId);
        jdbc.update("UPDATE image_candidate SET review_status='IGNORED',updated_at=CURRENT_TIMESTAMP "
                + "WHERE document_id=? AND id<>? AND review_status='PENDING'", documentId, candidateId);
        Map<String, Object> policy = sourcePolicy(documentId);
        if (Boolean.TRUE.equals(policy.get("image_cache_allowed"))) {
            cacheApprovedCandidate(candidate, sourceName.trim(), usageBasis.trim(), documentId);
        } else {
            jdbc.update("UPDATE source_document SET cover_image_url=NULL,cover_image_type='CATEGORY_DEFAULT',"
                            + "image_source_name='简达本地分类默认图',image_source_url=?,image_alt_text=?,image_cached=FALSE,"
                            + "image_license_note=?,image_width=NULL,image_height=NULL,image_hash=NULL,image_reviewed=TRUE,"
                            + "custom_cover_path=NULL,custom_cover_mime=NULL,custom_cover_filename=NULL WHERE id=?",
                    candidate.get("source_page_url"), candidate.get("alt_text"),
                    usageBasis.trim() + "；来源未允许本地缓存，公开端安全使用分类默认图", documentId);
        }
    }

    @Transactional
    public boolean autoApproveFirst(long documentId, AuthUser user) {
        Map<String, Object> policy = sourcePolicy(documentId);
        if (!Boolean.TRUE.equals(policy.get("auto_approve_images"))
                || !Boolean.TRUE.equals(policy.get("image_cache_allowed"))
                || policy.get("image_policy_reviewed_at") == null
                || text(policy.get("image_usage_basis")).isBlank()) {
            return false;
        }
        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT id FROM image_candidate WHERE document_id=? AND review_status='PENDING' "
                        + "ORDER BY relevance_score DESC,priority_rank,id LIMIT 1", documentId);
        if (pending.isEmpty()) return false;
        long candidateId = ((Number) pending.get(0).get("id")).longValue();
        approve(candidateId, text(policy.get("source_name")),
                text(policy.get("image_usage_basis")), user);
        jdbc.update("INSERT INTO operation_log(operator_id,organization_id,action,target_type,target_id,result,ip) "
                        + "VALUES (?,?,?,'SOURCE_DOCUMENT',?,'SUCCESS','policy')",
                user.id(), user.organizationId(), "AUTO_APPROVE_IMAGE_BY_SOURCE_POLICY", documentId);
        return true;
    }

    @Transactional
    public void reject(long candidateId, String reason, AuthUser user) {
        Map<String, Object> candidate = candidate(candidateId);
        long documentId = ((Number) candidate.get("document_id")).longValue();
        assertAccess(documentId, user);
        if (reason == null || reason.isBlank()) throw new BusinessException(400, "请填写拒绝原因");
        jdbc.update("UPDATE image_candidate SET rights_status='REJECTED',review_status='REJECTED',rejection_reason=?,"
                        + "reviewed_at=CURRENT_TIMESTAMP,reviewer_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                reason.trim(), user.id(), candidateId);
        jdbc.update("UPDATE source_document SET cover_image_url=NULL,cover_image_type='CATEGORY_DEFAULT',image_cached=FALSE,"
                        + "image_reviewed=TRUE,image_source_name='简达本地分类默认图',image_source_url=NULL,"
                        + "image_license_note='第三方图片未获确认，使用本地分类默认图' WHERE id=? AND cover_image_url=?",
                documentId, candidate.get("candidate_url"));
    }

    private Map<String, Object> candidate(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM image_candidate WHERE id=?", id);
        if (rows.isEmpty()) throw new BusinessException(404, "图片候选不存在");
        return rows.get(0);
    }

    private Map<String, Object> sourcePolicy(long documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.source_name,r.image_usage_policy,r.image_usage_basis,r.auto_approve_images,"
                        + "(r.image_cache_allowed OR r.allow_image_cache) image_cache_allowed,"
                        + "r.image_policy_reviewed_at "
                        + "FROM source_registry r JOIN crawl_job j ON j.source_registry_id=r.id "
                        + "WHERE j.document_id=? ORDER BY j.id DESC LIMIT 1", documentId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private void cacheApprovedCandidate(
            Map<String, Object> candidate, String sourceName, String usageBasis, long documentId) {
        AiClient.ImageAsset asset;
        try {
            asset = aiClient.fetchImage(text(candidate.get("candidate_url")));
        } catch (RuntimeException exception) {
            throw new BusinessException(502, "图片已通过人工确认，但本地缓存失败，请稍后重试");
        }
        String extension = extension(asset.contentType());
        Path directory = uploadRoot.resolve("covers").resolve("cached").normalize();
        Path target = directory.resolve(UUID.randomUUID() + extension).normalize();
        if (!target.startsWith(uploadRoot)) throw new BusinessException(400, "封面缓存路径不安全");
        try {
            Files.createDirectories(directory);
            Files.write(target, asset.bytes());
        } catch (IOException exception) {
            throw new BusinessException(500, "图片缓存写入失败");
        }
        jdbc.update("UPDATE source_document SET cover_image_url=NULL,cover_image_type=?,image_source_name=?,"
                        + "image_source_url=?,image_alt_text=?,image_cached=TRUE,image_license_note=?,image_width=?,"
                        + "image_height=?,image_hash=?,image_reviewed=TRUE,custom_cover_path=?,custom_cover_mime=?,"
                        + "custom_cover_filename=? WHERE id=?",
                coverType(candidate), sourceName, candidate.get("source_page_url"), candidate.get("alt_text"),
                usageBasis, asset.width() == null ? candidate.get("width") : asset.width(),
                asset.height() == null ? candidate.get("height") : asset.height(), sha256(asset.bytes()),
                target.toString(), asset.contentType(), "cached-cover" + extension, documentId);
        jdbc.update("UPDATE published_item SET cover_image_url=CONCAT('/api/public/items/',slug,'/cover') "
                + "WHERE document_id=? AND status='PUBLISHED'", documentId);
    }

    private static String extension(String contentType) {
        String normalized = text(contentType).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("image/png")) return ".png";
        if (normalized.startsWith("image/webp")) return ".webp";
        return ".jpg";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertAccess(long documentId, AuthUser user) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT organization_id FROM source_document WHERE id=? AND source_type='WEB_ARTICLE'", documentId);
        if (rows.isEmpty()) throw new BusinessException(404, "网页文章不存在");
        long organizationId = ((Number) rows.get(0).get("organization_id")).longValue();
        if (!"PLATFORM_ADMIN".equals(user.role()) && organizationId != user.organizationId()) {
            throw new BusinessException(403, "当前机构无权访问该材料");
        }
    }

    private static String coverType(Map<String, Object> candidate) {
        String method = text(candidate.get("discovery_method"));
        return Set.of("OPEN_GRAPH", "JSON_LD").contains(method) ? "ORIGINAL_COVER" : "ARTICLE_IMAGE";
    }

    private static String method(Object value) {
        String method = text(value).toUpperCase(Locale.ROOT);
        return Set.of("OPEN_GRAPH", "JSON_LD", "ARTICLE_IMAGE").contains(method) ? method : "ARTICLE_IMAGE";
    }

    private static boolean safeHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static Integer number(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static int relevance(Object value) {
        Integer score = number(value);
        return score == null ? 0 : Math.max(0, Math.min(100, score));
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String nullable(Object value) {
        String result = text(value);
        return result.isBlank() ? null : result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
