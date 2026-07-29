package cn.jianda.collector;

import cn.jianda.common.BusinessException;
import cn.jianda.security.AuthUser;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImageCandidateService {
    private final JdbcTemplate jdbc;

    public ImageCandidateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(long documentId, AuthUser user) {
        assertAccess(documentId, user);
        return jdbc.queryForList("SELECT id,document_id,candidate_url,source_page_url,source_name,alt_text,width,height,"
                + "mime_type,image_hash,image_cached,discovery_method,priority_rank,candidate_status,rights_status,"
                + "review_status,rejection_reason,usage_basis,created_at,updated_at,reviewed_at,reviewer_id "
                + "FROM image_candidate WHERE document_id=? ORDER BY priority_rank,id", documentId);
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
                            + "mime_type,image_hash,image_cached,discovery_method,priority_rank,candidate_status,rights_status,review_status) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'VALID','UNKNOWN','PENDING')",
                    documentId, url, sourcePage, "原网页", text(image.get("caption")), width, height,
                    nullable(image.get("mime_type")), nullable(image.get("image_hash")),
                    Boolean.TRUE.equals(image.get("image_cached")), method(image.get("discovery_method")), rank++);
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
        jdbc.update("UPDATE source_document SET cover_image_url=?,cover_image_type=?,image_source_name=?,image_source_url=?,"
                        + "image_alt_text=?,image_cached=?,image_license_note=?,image_width=?,image_height=?,image_hash=?,"
                        + "image_reviewed=TRUE,custom_cover_path=NULL,custom_cover_mime=NULL,custom_cover_filename=NULL WHERE id=?",
                candidate.get("candidate_url"), coverType(candidate), sourceName.trim(), candidate.get("source_page_url"),
                candidate.get("alt_text"), candidate.get("image_cached"), usageBasis.trim(), candidate.get("width"),
                candidate.get("height"), candidate.get("image_hash"), documentId);
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

    private static String nullable(Object value) {
        String result = text(value);
        return result.isBlank() ? null : result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
