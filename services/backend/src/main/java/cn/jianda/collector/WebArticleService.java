package cn.jianda.collector;

import cn.jianda.ai.AiClient;
import cn.jianda.ai.AiQueueService;
import cn.jianda.common.BusinessException;
import cn.jianda.security.AuthUser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebArticleService {
    private static final long PREVIEW_TTL_SECONDS = 15 * 60;
    private final JdbcTemplate jdbc;
    private final AiClient aiClient;
    private final AiQueueService aiQueueService;
    private final ImageCandidateService imageCandidateService;
    private final boolean webImageCandidatesEnabled;
    private final Map<String, CachedPreview> previews = new ConcurrentHashMap<>();

    public WebArticleService(JdbcTemplate jdbc, AiClient aiClient, AiQueueService aiQueueService,
                             ImageCandidateService imageCandidateService,
                             @Value("${jianda.crawl.web-image-candidates-enabled:true}")
                             boolean webImageCandidatesEnabled) {
        this.jdbc = jdbc;
        this.aiClient = aiClient;
        this.aiQueueService = aiQueueService;
        this.imageCandidateService = imageCandidateService;
        this.webImageCandidatesEnabled = webImageCandidatesEnabled;
    }

    public List<Map<String, Object>> registries() {
        return jdbc.queryForList("SELECT * FROM source_registry ORDER BY authority_level,source_name");
    }

    public List<Map<String, Object>> crawlJobs() {
        return jdbc.queryForList(
                "SELECT j.*,r.source_name,r.domain FROM crawl_job j "
                        + "JOIN source_registry r ON r.id=j.source_registry_id "
                        + "ORDER BY j.updated_at DESC,j.id DESC");
    }

    public void stopJob(long jobId) {
        int changed = jdbc.update(
                "UPDATE crawl_job SET status='STOPPED',updated_at=CURRENT_TIMESTAMP "
                        + "WHERE id=? AND status IN ('QUEUED','RUNNING')", jobId);
        if (changed == 0) {
            throw new BusinessException(409, "当前采集任务已经结束，不能停止");
        }
    }

    public Map<String, Object> preview(String rawUrl) {
        String url = normalizeUrl(rawUrl);
        Map<String, Object> registry = registryFor(url);
        CachedPreview cached = previews.get(url);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.value();
        }
        Map<String, Object> result;
        try {
            result = new LinkedHashMap<>(aiClient.previewWebArticle(
                    url,
                    webImageCandidatesEnabled
                            && Boolean.TRUE.equals(registry.get("allow_image_candidates"))
            ));
        } catch (RuntimeException exception) {
            throw new BusinessException(502, safeMessage(exception, "网页暂时无法访问或解析"));
        }
        String canonical = normalizeUrl(String.valueOf(result.getOrDefault("canonical_url", url)));
        registryFor(canonical);
        result.put("original_url", url);
        result.put("canonical_url", canonical);
        String originalDomain = URI.create(url).getHost().toLowerCase(Locale.ROOT);
        String canonicalDomain = URI.create(canonical).getHost().toLowerCase(Locale.ROOT);
        result.put("source_domain", canonicalDomain);
        result.put("original_domain", originalDomain);
        result.put("canonical_domain", canonicalDomain);
        result.put("canonical_cross_domain", !originalDomain.equals(canonicalDomain));
        result.put("canonical_confirmation_required", false);
        result.put("source_name", text(result.get("source_name")).isBlank()
                ? registry.get("source_name") : text(result.get("source_name")));
        result.put("authority_level", registry.get("authority_level"));
        result.put("source_registry_id", registry.get("id"));
        boolean allowImageCache = Boolean.TRUE.equals(registry.get("allow_image_cache"));
        boolean allowImageCandidates = webImageCandidatesEnabled
                && Boolean.TRUE.equals(registry.get("allow_image_candidates"));
        result.put("allow_image_cache", allowImageCache);
        result.put("allow_image_candidates", allowImageCandidates);
        if (!allowImageCandidates) {
            result.put("cover_image_url", "");
            result.put("cover_image_type", "CATEGORY_DEFAULT");
            result.put("image_alt_text", text(result.get("title")));
            result.put("image_width", null);
            result.put("image_height", null);
            result.put("image_hash", "");
            result.put("image_validated", false);
        }
        result.put("image_cached", false);
        result.put("image_source_name", result.get("source_name"));
        result.put("image_source_url", canonical);
        result.put("image_license_note", allowImageCache
                ? "白名单允许在人工确认来源和使用依据后缓存，当前候选尚未公开"
                : "仅生成机构端审核候选，不缓存或公开第三方图片");
        result.put("external_source_verified", true);
        result.put("trust_status", "VERIFIED");
        previews.put(url, new CachedPreview(Instant.now().plusSeconds(PREVIEW_TTL_SECONDS), result));
        return result;
    }

    public Map<String, Object> previewUnregistered(String rawUrl) {
        String url = normalizeUrl(rawUrl);
        Map<String, Object> raw;
        try {
            raw = new LinkedHashMap<>(aiClient.previewWebArticle(url, webImageCandidatesEnabled));
        } catch (RuntimeException exception) {
            throw new BusinessException(502, safeMessage(exception, "网页暂时无法访问或解析"));
        }
        String canonical = normalizeUrl(String.valueOf(raw.getOrDefault("canonical_url", url)));
        URI originalUri = URI.create(url);
        URI canonicalUri = URI.create(canonical);
        boolean canonicalCrossDomain =
                !originalUri.getHost().equalsIgnoreCase(canonicalUri.getHost());
        String domain = canonicalUri.getHost().toLowerCase(Locale.ROOT);
        String accountName = text(raw.get("wechat_account_name"));
        String accountSubject = text(raw.get("account_subject"));
        String wechatBiz = text(raw.get("wechat_biz"));
        boolean wechat = "mp.weixin.qq.com".equals(domain);
        String identitySeed = identitySeed(domain, accountName, accountSubject, wechatBiz);
        List<Map<String, Object>> registries = jdbc.queryForList(
                "SELECT id,source_name,source_type,enabled FROM source_registry WHERE LOWER(domain)=?", domain);
        List<Map<String, Object>> identities = jdbc.queryForList(
                "SELECT i.id,i.source_registry_id,i.official_verified,i.verification_note "
                        + "FROM source_registry_identity i WHERE i.source_identity_fingerprint=?",
                sha256(identitySeed));
        Map<String, Object> result = new LinkedHashMap<>(raw);
        result.put("original_url", url);
        result.put("canonical_url", canonical);
        result.put("domain", domain);
        result.put("original_domain", originalUri.getHost().toLowerCase(Locale.ROOT));
        result.put("canonical_domain", domain);
        result.put("canonical_cross_domain", canonicalCrossDomain);
        result.put("canonical_confirmation_required", canonicalCrossDomain);
        result.put("https", "https".equalsIgnoreCase(canonicalUri.getScheme()));
        result.put("page_title", text(raw.get("title")));
        result.put("source_name", text(raw.get("source_name")));
        result.put("wechat_account_name", accountName);
        result.put("account_subject", accountSubject);
        result.put("wechat_biz", wechatBiz);
        result.put("wechat_article", wechat);
        result.put("source_identity_fingerprint", sha256(identitySeed));
        result.put("source_type_suggestion", wechat ? "OFFICIAL_WECHAT" : "OTHER_VERIFIED_OFFICIAL");
        result.put("robots_allowed", raw.get("robots_allowed"));
        result.put("robots_status", raw.get("robots_status"));
        result.put("content_kind", raw.get("content_kind"));
        result.put("cover_image_url", raw.get("cover_image_url"));
        result.put("images", raw.getOrDefault("images", List.of()));
        result.put("warnings", raw.getOrDefault("warnings", List.of()));
        result.put("registered_source", registries.isEmpty() ? null : registries.get(0));
        result.put("verified_identity", identities.isEmpty() ? null : identities.get(0));
        result.put("official_verified", !identities.isEmpty()
                && Boolean.TRUE.equals(identities.get(0).get("official_verified")));
        result.put("authority_level", "UNVERIFIED");
        result.put("external_source_verified", false);
        result.put("allow_image_cache", false);
        result.put("allow_image_candidates", webImageCandidatesEnabled);
        result.put("image_cached", false);
        result.put("image_reviewed", false);
        result.put("image_source_name", text(raw.get("source_name")));
        result.put("image_source_url", canonical);
        result.put("image_license_note", "未核验网页图片仅作为机构端候选，人工确认前不得公开");
        result.put("trust_status", "UNVERIFIED");
        result.put("content_preview", text(raw.get("content_preview")).isBlank()
                ? abbreviate(text(raw.get("extracted_text")), 500)
                : text(raw.get("content_preview")));
        previews.put(url, new CachedPreview(Instant.now().plusSeconds(PREVIEW_TTL_SECONDS), result));
        return result;
    }

    public Map<String, Object> previewAny(String rawUrl) {
        String url = normalizeUrl(rawUrl);
        URI uri = URI.create(url);
        Integer enabled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_registry WHERE LOWER(domain)=? AND enabled=TRUE",
                Integer.class, uri.getHost().toLowerCase(Locale.ROOT));
        return enabled != null && enabled > 0 ? preview(url) : previewUnregistered(url);
    }

    @Transactional
    public Map<String, Object> importArticle(String url, AuthUser user) {
        Map<String, Object> preview = preview(url);
        String canonical = text(preview.get("canonical_url"));
        String contentHash = text(preview.get("content_hash"));
        Integer duplicate = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_document WHERE canonical_url=? OR content_hash=?",
                Integer.class, canonical, contentHash);
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException(409, "该网页或相同正文已经导入，请勿重复操作");
        }
        return persistRegisteredArticle(preview, user);
    }

    @Transactional
    public Map<String, Object> importOneOffArticle(
            String rawUrl, boolean canonicalConfirmed, AuthUser user) {
        Map<String, Object> preview = previewUnregistered(rawUrl);
        if (Boolean.TRUE.equals(preview.get("canonical_confirmation_required"))
                && !canonicalConfirmed) {
            throw new BusinessException(409, "网页最终域名与输入域名不同，请人工确认后仅本次导入");
        }
        String canonical = text(preview.get("canonical_url"));
        String contentHash = text(preview.get("content_hash"));
        Integer duplicate = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_document WHERE canonical_url=? OR content_hash=?",
                Integer.class, canonical, contentHash);
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException(409, "该网页或相同正文已经导入，请勿重复操作");
        }
        return persistOneOffArticle(preview, user);
    }

    @Transactional
    public Map<String, Object> importPastedArticle(
            String rawUrl, String rawTitle, String rawSourceName,
            String rawBody, String rawContentKind, AuthUser user) {
        String url = normalizeUrl(rawUrl);
        String title = text(rawTitle).trim();
        String body = text(rawBody).trim();
        if (title.isBlank() || body.isBlank()) {
            throw new BusinessException(400, "标题和粘贴正文不能为空");
        }
        String contentHash = sha256(body);
        Integer duplicate = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_document WHERE canonical_url=? OR content_hash=?",
                Integer.class, url, contentHash);
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException(409, "该网页地址或相同正文已经导入，请勿重复操作");
        }
        String domain = URI.create(url).getHost().toLowerCase(Locale.ROOT);
        String contentKind = text(rawContentKind).trim();
        if (!List.of("HEALTH_EDUCATION", "POLICY_NEWS", "ANTI_FRAUD",
                "COMMUNITY_SERVICE", "SERVICE_NOTICE", "CULTURE_EDUCATION").contains(contentKind)) {
            contentKind = "SERVICE_NOTICE";
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("original_url", url);
        preview.put("canonical_url", url);
        preview.put("source_domain", domain);
        preview.put("source_name", defaultString(text(rawSourceName).trim(), domain));
        preview.put("title", title);
        preview.put("extracted_text", body);
        preview.put("content_hash", contentHash);
        preview.put("content_kind", contentKind);
        preview.put("cover_image_type", "CATEGORY_DEFAULT");
        preview.put("images", List.of());
        preview.put("robots_status", "MANUAL_PASTE");
        preview.put("original_page_available", false);
        preview.put("image_source_name", "简达分类默认图");
        preview.put("image_source_url", url);
        preview.put("image_alt_text", title);
        preview.put("image_license_note", "网页正文由机构人员手工粘贴，未抓取或使用第三方图片");
        return persistOneOffArticle(preview, user);
    }

    @Transactional
    public int rescanImageCandidates(long documentId, AuthUser user) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT organization_id,source_type,original_url,canonical_url,external_source_verified "
                        + "FROM source_document WHERE id=?",
                documentId);
        if (rows.isEmpty() || !"WEB_ARTICLE".equals(rows.get(0).get("source_type"))) {
            throw new BusinessException(404, "网页文章不存在");
        }
        Number organization = (Number) rows.get(0).get("organization_id");
        if (!"PLATFORM_ADMIN".equals(user.role())
                && (organization == null || organization.longValue() != user.organizationId())) {
            throw new BusinessException(403, "当前机构无权访问该材料");
        }
        String original = text(rows.get(0).get("original_url"));
        if (original.isBlank()) original = text(rows.get(0).get("canonical_url"));
        Map<String, Object> refreshed = Boolean.TRUE.equals(rows.get(0).get("external_source_verified"))
                ? preview(original)
                : previewUnregistered(original);
        imageCandidateService.persist(documentId, text(refreshed.get("canonical_url")), refreshed.get("images"));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM image_candidate WHERE document_id=? AND review_status='PENDING'",
                Integer.class, documentId);
        return count == null ? 0 : count;
    }

    @Transactional
    public Map<String, Object> importApprovedArticle(String rawUrl, long registryId, AuthUser user) {
        Map<String, Object> registry = jdbc.queryForMap("SELECT * FROM source_registry WHERE id=?", registryId);
        String url = normalizeUrl(rawUrl);
        Map<String, Object> preview;
        try {
            preview = new LinkedHashMap<>(aiClient.previewWebArticle(
                    url, Boolean.TRUE.equals(registry.get("allow_image_candidates"))));
        } catch (RuntimeException exception) {
            throw new BusinessException(502, safeMessage(exception, "网页暂时无法访问或解析"));
        }
        String canonical = normalizeUrl(String.valueOf(preview.getOrDefault("canonical_url", url)));
        String domain = URI.create(canonical).getHost().toLowerCase(Locale.ROOT);
        if (!domain.equalsIgnoreCase(text(registry.get("domain")))) {
            throw new BusinessException(400, "网页 canonical 地址与已确认来源不同");
        }
        String fingerprint = identityFingerprint(domain, preview);
        Integer verified = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_registry_identity WHERE source_registry_id=? "
                        + "AND source_identity_fingerprint=? AND official_verified=TRUE",
                Integer.class, registryId, fingerprint);
        if (verified == null || verified == 0) {
            throw new BusinessException(403, "网页来源身份与管理员已确认的官方身份不一致");
        }
        preview.put("original_url", url);
        preview.put("canonical_url", canonical);
        preview.put("source_domain", domain);
        preview.put("source_name", text(preview.get("source_name")).isBlank()
                ? registry.get("source_name") : text(preview.get("source_name")));
        preview.put("authority_level", registry.get("authority_level"));
        preview.put("source_registry_id", registryId);
        boolean allowImageCandidates = Boolean.TRUE.equals(registry.get("allow_image_candidates"));
        preview.put("allow_image_cache", Boolean.TRUE.equals(registry.get("image_cache_allowed")));
        preview.put("allow_image_candidates", allowImageCandidates);
        preview.put("image_cached", false);
        preview.put("image_source_name", preview.get("source_name"));
        preview.put("image_source_url", canonical);
        preview.put("image_license_note", "来源身份已确认；图片仍按来源策略和候选审核规则处理");
        preview.put("external_source_verified", true);
        if (!allowImageCandidates) {
            preview.put("cover_image_url", "");
            preview.put("cover_image_type", "CATEGORY_DEFAULT");
            preview.put("images", List.of());
        }
        String contentHash = text(preview.get("content_hash"));
        Integer duplicate = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_document WHERE canonical_url=? OR content_hash=?",
                Integer.class, canonical, contentHash);
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException(409, "该网页或相同正文已经导入，请勿重复操作");
        }
        return persistRegisteredArticle(preview, user);
    }

    private Map<String, Object> persistRegisteredArticle(
            Map<String, Object> preview, AuthUser user) {
        return persistArticle(preview, user, number(preview.get("source_registry_id")));
    }

    private Map<String, Object> persistOneOffArticle(
            Map<String, Object> preview, AuthUser user) {
        preview.put("source_registry_id", null);
        preview.put("authority_level", "UNVERIFIED");
        preview.put("external_source_verified", false);
        preview.put("allow_image_cache", false);
        preview.put("image_cached", false);
        preview.put("image_reviewed", false);
        return persistArticle(preview, user, null);
    }

    private Map<String, Object> persistArticle(
            Map<String, Object> preview, AuthUser user, Long registryId) {
        String canonical = text(preview.get("canonical_url"));
        String contentHash = text(preview.get("content_hash"));
        long sourceId = registryId == null
                ? ensureOneOffContentSource(preview, user)
                : ensureContentSource(registryId, preview);
        String body = text(preview.get("extracted_text"));
        String contentKind = text(preview.get("content_kind"));
        String category = categoryFor(contentKind);
        boolean categoryDefault = "CATEGORY_DEFAULT".equals(preview.get("cover_image_type"));
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO source_document(organization_id,content_source_id,title,file_type,raw_text,page_count,"
                            + "processing_status,created_by,import_url,source_published_at,imported_at,content_hash,category,"
                            + "import_method,source_type,original_url,canonical_url,source_domain,source_name,"
                            + "source_authority_level,article_author,original_published_at,crawl_time,cover_image_url,"
                            + "cover_image_type,image_source_name,image_source_url,image_alt_text,image_cached,"
                            + "image_license_note,image_width,image_height,image_hash,image_reviewed,original_html,"
                            + "extracted_text,crawl_status,robots_status,original_page_available,external_source_verified,"
                            + "content_kind,prompt_version,schema_version) "
                            + "VALUES (?,?,?,'text/html',?,1,'UPLOADED',?,?,?,?,?,?,'WEB_URL','WEB_ARTICLE',?,?,?,?,?,?,?,?,"
                            + "?,?,?,?,?,?,?,?,?,?,?,?,?,'SUCCEEDED',?,?,?,?,'web-v1.1','1.1')",
                    new String[] {"id"});
            int index = 1;
            statement.setLong(index++, user.organizationId());
            statement.setLong(index++, sourceId);
            statement.setString(index++, text(preview.get("title")));
            statement.setString(index++, body);
            statement.setLong(index++, user.id());
            statement.setString(index++, text(preview.get("original_url")));
            statement.setTimestamp(index++, timestamp(preview.get("published_at")));
            statement.setTimestamp(index++, Timestamp.from(Instant.now()));
            statement.setString(index++, contentHash);
            statement.setString(index++, category);
            statement.setString(index++, text(preview.get("original_url")));
            statement.setString(index++, canonical);
            statement.setString(index++, text(preview.get("source_domain")));
            statement.setString(index++, text(preview.get("source_name")));
            statement.setString(index++, text(preview.get("authority_level")));
            statement.setString(index++, text(preview.get("author")));
            statement.setTimestamp(index++, timestamp(preview.get("published_at")));
            statement.setTimestamp(index++, Timestamp.from(Instant.now()));
            statement.setString(index++, nullable(preview.get("cover_image_url")));
            statement.setString(index++, text(preview.get("cover_image_type")));
            statement.setString(index++, text(preview.get("image_source_name")));
            statement.setString(index++, text(preview.get("image_source_url")));
            statement.setString(index++, text(preview.get("image_alt_text")));
            statement.setBoolean(index++, false);
            statement.setString(index++, text(preview.get("image_license_note")));
            setNullableInt(statement, index++, preview.get("image_width"));
            setNullableInt(statement, index++, preview.get("image_height"));
            statement.setString(index++, nullable(preview.get("image_hash")));
            statement.setBoolean(index++, categoryDefault);
            statement.setString(index++, text(preview.get("original_html")));
            statement.setString(index++, body);
            statement.setString(index++, text(preview.get("robots_status")));
            statement.setBoolean(index++, !Boolean.FALSE.equals(preview.get("original_page_available")));
            statement.setBoolean(index++, Boolean.TRUE.equals(preview.get("external_source_verified")));
            statement.setString(index, contentKind);
            return statement;
        }, keys);
        Number generatedDocumentId = keys.getKey();
        if (generatedDocumentId == null) throw new IllegalStateException("未取得网页文章编号");
        long documentId = generatedDocumentId.longValue();
        jdbc.update("UPDATE source_document SET version_root_id=id,new_content_hash=content_hash WHERE id=?", documentId);
        imageCandidateService.persist(documentId, canonical, preview.get("images"));
        jdbc.update("INSERT INTO document_segment(document_id,page_no,segment_no,text,start_offset,end_offset) VALUES (?,1,1,?,0,?)",
                documentId, body, body.length());
        Long crawlJobId = null;
        if (registryId != null) {
            Integer canonicalJobCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM crawl_job WHERE source_registry_id=? AND canonical_url=?",
                    Integer.class, registryId, text(preview.get("canonical_url")));
            String jobCanonical = canonicalJobCount != null && canonicalJobCount > 0
                    ? null : text(preview.get("canonical_url"));
            jdbc.update("INSERT INTO crawl_job(source_registry_id,document_id,original_url,canonical_url,status,trigger_type,"
                            + "processing_stage,discovered_at,started_at,finished_at,last_success_at,discovered_count,added_count,created_by) "
                            + "VALUES (?,?,?,?,'SUCCESS','MANUAL','IMPORT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,"
                            + "CURRENT_TIMESTAMP,1,1,?)",
                    registryId, documentId, text(preview.get("original_url")), jobCanonical, user.id());
            crawlJobId = jdbc.queryForObject(
                    "SELECT MAX(id) FROM crawl_job WHERE document_id=?", Long.class, documentId);
            jdbc.update("UPDATE source_registry SET last_crawled_at=CURRENT_TIMESTAMP WHERE id=?", registryId);
        }
        jdbc.update("UPDATE content_source SET last_imported_at=CURRENT_TIMESTAMP WHERE id=?", sourceId);
        Map<String, Object> queued = aiQueueService.enqueue(documentId, registryId, crawlJobId);
        log(user, "IMPORT_WEB_ARTICLE", documentId, "SUCCESS");
        return Map.of("documentId", documentId, "status", "UPLOADED", "contentKind", contentKind,
                "imageReviewRequired", !categoryDefault, "aiQueueStatus", queued.get("status"));
    }

    @Transactional
    public void useCategoryDefault(long documentId, AuthUser user) {
        assertAccess(documentId, user);
        int changed = jdbc.update("UPDATE source_document SET cover_image_url=NULL,cover_image_type='CATEGORY_DEFAULT',"
                + "image_cached=FALSE,image_reviewed=TRUE,custom_cover_path=NULL,custom_cover_mime=NULL,"
                + "custom_cover_filename=NULL WHERE id=? AND source_type='WEB_ARTICLE'", documentId);
        if (changed == 0) throw new BusinessException(404, "网页文章不存在");
        jdbc.update("UPDATE image_candidate SET review_status='IGNORED',updated_at=CURRENT_TIMESTAMP "
                + "WHERE document_id=? AND review_status='PENDING'", documentId);
        log(user, "USE_CATEGORY_DEFAULT_COVER", documentId, "SUCCESS");
    }

    @Transactional
    public void selectArticleCover(long documentId, String imageUrl, AuthUser user) {
        assertAccess(documentId, user);
        String normalized = normalizeUrl(imageUrl);
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT id FROM image_candidate WHERE document_id=? AND candidate_url=? AND candidate_status='VALID'",
                documentId, normalized);
        if (candidates.isEmpty()) throw new BusinessException(400, "所选图片不在已验证候选列表中");
        jdbc.update("UPDATE source_document SET cover_image_url=?,cover_image_type='ARTICLE_IMAGE',"
                        + "image_source_name='待人工确认',image_source_url=canonical_url,image_cached=FALSE,"
                        + "image_license_note='候选图片尚未确认来源和许可，不得发布',"
                        + "image_reviewed=FALSE,custom_cover_path=NULL,custom_cover_mime=NULL,"
                        + "custom_cover_filename=NULL WHERE id=?",
                normalized, documentId);
        log(user, "SELECT_ARTICLE_COVER", documentId, "SUCCESS");
    }

    @Transactional
    public void confirmCover(long documentId, AuthUser user) {
        assertAccess(documentId, user);
        Integer cached = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_document WHERE id=? AND source_type='WEB_ARTICLE' "
                        + "AND image_reviewed=TRUE AND custom_cover_path IS NOT NULL",
                Integer.class, documentId);
        if (cached != null && cached > 0) {
            log(user, "CONFIRM_WEB_COVER", documentId, "SUCCESS");
            return;
        }
        Integer approved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM image_candidate WHERE document_id=? AND candidate_url=("
                        + "SELECT cover_image_url FROM source_document WHERE id=?) AND review_status='APPROVED' "
                        + "AND rights_status='CONFIRMED' AND usage_basis IS NOT NULL",
                Integer.class, documentId, documentId);
        if (approved == null || approved == 0) {
            throw new BusinessException(400, "请先在图片候选中填写来源和许可说明并确认可用");
        }
        int changed = jdbc.update("UPDATE source_document SET image_reviewed=TRUE WHERE id=? "
                + "AND source_type='WEB_ARTICLE' AND cover_image_url IS NOT NULL", documentId);
        if (changed == 0) throw new BusinessException(400, "没有可确认的原文封面");
        log(user, "CONFIRM_WEB_COVER", documentId, "SUCCESS");
    }

    @Transactional
    public Map<String, Object> recrawl(long documentId, AuthUser user) {
        assertAccess(documentId, user);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM source_document WHERE id=? AND source_type='WEB_ARTICLE'",
                documentId);
        if (rows.isEmpty()) throw new BusinessException(404, "网页文章不存在");
        Map<String, Object> current = rows.get(0);
        String originalUrl = text(current.get("original_url"));
        previews.remove(originalUrl);
        Map<String, Object> refreshed = preview(originalUrl);
        String refreshedCanonical = text(refreshed.get("canonical_url"));
        if (!refreshedCanonical.equals(text(current.get("canonical_url")))) {
            throw new BusinessException(409, "重新采集返回了不同 canonical URL，请人工核对来源");
        }
        String refreshedHash = text(refreshed.get("content_hash"));
        if (refreshedHash.equals(text(current.get("content_hash")))) {
            jdbc.update("UPDATE crawl_job SET status='UNCHANGED',last_success_at=CURRENT_TIMESTAMP,"
                            + "last_error=NULL,content_changed=FALSE,updated_at=CURRENT_TIMESTAMP WHERE document_id=?",
                    documentId);
            jdbc.update("UPDATE source_registry SET last_crawled_at=CURRENT_TIMESTAMP WHERE id=("
                            + "SELECT source_registry_id FROM crawl_job WHERE document_id=? ORDER BY id DESC LIMIT 1)",
                    documentId);
            log(user, "RECRAWL_WEB_ARTICLE", documentId, "UNCHANGED");
            return Map.of("documentId", documentId, "status", current.get("processing_status"),
                    "contentKind", text(current.get("content_kind")),
                    "contentChanged", false, "cacheHit", true);
        }
        if ("PUBLISHED".equals(current.get("processing_status"))) {
            long rootId = current.get("version_root_id") instanceof Number root
                    ? root.longValue() : documentId;
            Integer nextVersionValue = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(version_no),0)+1 FROM source_document WHERE version_root_id=?",
                    Integer.class, rootId);
            int nextVersion = nextVersionValue == null ? 2 : nextVersionValue;
            Map<String, Object> created = persistRegisteredArticle(refreshed, user);
            long newDocumentId = number(created.get("documentId"));
            String oldHash = text(current.get("content_hash"));
            jdbc.update("UPDATE source_document SET previous_version_id=?,version_root_id=?,version_no=?,"
                            + "old_content_hash=?,new_content_hash=?,content_change_summary=?,version_created_at=CURRENT_TIMESTAMP,"
                            + "processing_status='UPLOADED' WHERE id=?",
                    documentId, rootId, nextVersion, oldHash, refreshedHash,
                    changeSummary(text(current.get("raw_text")), text(refreshed.get("extracted_text")), oldHash, refreshedHash),
                    newDocumentId);
            jdbc.update("UPDATE crawl_job SET content_changed=TRUE,last_success_at=CURRENT_TIMESTAMP,"
                            + "last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE document_id=?",
                    documentId);
            log(user, "CREATE_WEB_ARTICLE_VERSION", newDocumentId, "UPLOADED");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("documentId", newDocumentId);
            response.put("previousDocumentId", documentId);
            response.put("versionNo", nextVersion);
            response.put("oldHash", oldHash);
            response.put("newHash", refreshedHash);
            response.put("changeSummary", changeSummary(text(current.get("raw_text")), text(refreshed.get("extracted_text")), oldHash, refreshedHash));
            response.put("status", "UPLOADED");
            response.put("aiQueueStatus", created.get("aiQueueStatus"));
            response.put("contentKind", text(refreshed.get("content_kind")));
            response.put("contentChanged", true);
            response.put("cacheHit", false);
            return response;
        }
        String canonical = text(refreshed.get("canonical_url"));
        Integer duplicate = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_document WHERE id<>? AND (canonical_url=? OR content_hash=?)",
                Integer.class, documentId, canonical, refreshedHash);
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException(409, "重新采集结果与其他材料重复");
        }
        String body = text(refreshed.get("extracted_text"));
        String contentKind = text(refreshed.get("content_kind"));
        boolean categoryDefault = "CATEGORY_DEFAULT".equals(refreshed.get("cover_image_type"));

        jdbc.update("DELETE FROM image_candidate WHERE document_id=?", documentId);
        jdbc.update("DELETE FROM review_record WHERE document_id=?", documentId);
        jdbc.update("DELETE FROM extracted_field WHERE document_id=?", documentId);
        jdbc.update("DELETE FROM generated_content WHERE document_id=?", documentId);
        jdbc.update("DELETE FROM processing_job WHERE document_id=?", documentId);
        jdbc.update("DELETE FROM document_segment WHERE document_id=?", documentId);
        jdbc.update("UPDATE source_document SET title=?,raw_text=?,content_hash=?,category=?,canonical_url=?,"
                        + "source_domain=?,source_name=?,source_authority_level=?,article_author=?,"
                        + "original_published_at=?,source_published_at=?,crawl_time=CURRENT_TIMESTAMP,"
                        + "cover_image_url=?,cover_image_type=?,image_source_name=?,image_source_url=?,"
                        + "image_alt_text=?,image_cached=FALSE,image_license_note=?,image_width=?,image_height=?,"
                        + "image_hash=?,image_reviewed=?,original_html=?,extracted_text=?,crawl_status='SUCCEEDED',"
                        + "robots_status=?,original_page_available=TRUE,external_source_verified=TRUE,"
                        + "content_kind=?,processing_status='UPLOADED',updated_at=CURRENT_TIMESTAMP WHERE id=?",
                text(refreshed.get("title")), body, text(refreshed.get("content_hash")),
                categoryFor(contentKind), canonical, text(refreshed.get("source_domain")),
                text(refreshed.get("source_name")), text(refreshed.get("authority_level")),
                text(refreshed.get("author")), timestamp(refreshed.get("published_at")),
                timestamp(refreshed.get("published_at")), nullable(refreshed.get("cover_image_url")),
                text(refreshed.get("cover_image_type")), text(refreshed.get("image_source_name")),
                text(refreshed.get("image_source_url")), text(refreshed.get("image_alt_text")),
                text(refreshed.get("image_license_note")), refreshed.get("image_width"),
                refreshed.get("image_height"), nullable(refreshed.get("image_hash")),
                categoryDefault, text(refreshed.get("original_html")), body,
                text(refreshed.get("robots_status")), contentKind, documentId);
        imageCandidateService.persist(documentId, canonical, refreshed.get("images"));
        jdbc.update("INSERT INTO document_segment(document_id,page_no,segment_no,text,start_offset,end_offset) "
                        + "VALUES (?,1,1,?,0,?)", documentId, body, body.length());
        jdbc.update("UPDATE crawl_job SET status='SUCCEEDED',last_success_at=CURRENT_TIMESTAMP,"
                        + "last_error=NULL,content_changed=TRUE,updated_at=CURRENT_TIMESTAMP WHERE document_id=?",
                documentId);
        log(user, "RECRAWL_WEB_ARTICLE", documentId, "SUCCESS");
        return Map.of("documentId", documentId, "status", "UPLOADED",
                "contentKind", contentKind, "contentChanged", true);
    }

    private Map<String, Object> registryFor(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "请输入有效的 HTTP 或 HTTPS 网址");
        }
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new BusinessException(400, "请输入不含账号信息的公开 HTTP 或 HTTPS 网址");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM source_registry WHERE LOWER(domain)=? AND enabled=TRUE",
                uri.getHost().toLowerCase(Locale.ROOT));
        if (rows.isEmpty()) throw new BusinessException(403, "该域名不在权威来源白名单中");
        return rows.get(0);
    }

    private long ensureContentSource(long registryId, Map<String, Object> preview) {
        Map<String, Object> registry = jdbc.queryForMap("SELECT * FROM source_registry WHERE id=?", registryId);
        String root = URI.create(text(preview.get("canonical_url"))).resolve("/").toString();
        List<Long> ids = jdbc.query("SELECT id FROM content_source WHERE source_url=? ORDER BY id LIMIT 1",
                (row, index) -> row.getLong(1), root);
        if (!ids.isEmpty()) return ids.get(0);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO content_source(organization_id,source_type,source_name,source_url,publisher,status,"
                            + "whitelist_status,enabled,notes) VALUES (NULL,'WEB_ARTICLE',?,?,?,'ACTIVE','APPROVED',TRUE,?)",
                    new String[] {"id"});
            statement.setString(1, text(registry.get("source_name")));
            statement.setString(2, root);
            statement.setString(3, text(registry.get("source_name")));
            statement.setString(4, "由 source_registry 白名单同步");
            return statement;
        }, keys);
        Number generatedSourceId = keys.getKey();
        if (generatedSourceId == null) throw new IllegalStateException("未取得内容来源编号");
        return generatedSourceId.longValue();
    }

    private long ensureOneOffContentSource(
            Map<String, Object> preview, AuthUser user) {
        String root = URI.create(text(preview.get("canonical_url"))).resolve("/").toString();
        List<Long> ids = jdbc.query(
                "SELECT id FROM content_source WHERE organization_id=? AND source_url=? "
                        + "AND whitelist_status='UNVERIFIED' ORDER BY id LIMIT 1",
                (row, index) -> row.getLong(1), user.organizationId(), root);
        if (!ids.isEmpty()) return ids.get(0);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO content_source(organization_id,source_type,source_name,source_url,publisher,status,"
                            + "whitelist_status,enabled,notes) VALUES (?,'WEB_ARTICLE',?,?,?,'ACTIVE','UNVERIFIED',TRUE,?)",
                    new String[] {"id"});
            statement.setLong(1, user.organizationId());
            statement.setString(2, defaultString(text(preview.get("source_name")),
                    URI.create(root).getHost()));
            statement.setString(3, root);
            statement.setString(4, defaultString(text(preview.get("source_name")), "未核验网页"));
            statement.setString(5, "仅本次手工导入；不进入自动采集，发布前必须人工核验来源");
            return statement;
        }, keys);
        Number generatedSourceId = keys.getKey();
        if (generatedSourceId == null) throw new IllegalStateException("未取得内容来源编号");
        return generatedSourceId.longValue();
    }

    private static String normalizeUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        try {
            URI uri = URI.create(trimmed);
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return uri.normalize().toString();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "请输入有效的 HTTP 或 HTTPS 网址");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String identityFingerprint(String domain, Map<String, Object> preview) {
        return sha256(identitySeed(domain, text(preview.get("wechat_account_name")),
                text(preview.get("account_subject")), text(preview.get("wechat_biz"))));
    }

    private static String identitySeed(String domain, String accountName, String accountSubject, String wechatBiz) {
        return "mp.weixin.qq.com".equalsIgnoreCase(domain)
                ? domain.toLowerCase(Locale.ROOT) + "|"
                        + (wechatBiz.isBlank() ? accountName + "|" + accountSubject : wechatBiz)
                : domain.toLowerCase(Locale.ROOT);
    }

    private static String categoryFor(String contentKind) {
        return switch (contentKind) {
            case "HEALTH_EDUCATION" -> "健康";
            case "POLICY_NEWS" -> "养老政策";
            case "ANTI_FRAUD" -> "防诈";
            case "COMMUNITY_SERVICE" -> "社区服务";
            case "SERVICE_NOTICE" -> "办事通知";
            default -> "文化学习";
        };
    }

    private static Timestamp timestamp(Object value) {
        String text = text(value);
        if (text.isBlank()) return null;
        try {
            return Timestamp.from(OffsetDateTime.parse(text).toInstant());
        } catch (DateTimeParseException ignored) {
            try {
                return Timestamp.valueOf(text.replace("T", " ").substring(0, 19));
            } catch (RuntimeException ignoredAgain) {
                return null;
            }
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Object value)
            throws java.sql.SQLException {
        if (value instanceof Number number) statement.setInt(index, number.intValue());
        else statement.setNull(index, java.sql.Types.INTEGER);
    }

    private static String changeSummary(String oldText, String newText, String oldHash, String newHash) {
        int oldLength = oldText.length();
        int newLength = newText.length();
        int delta = newLength - oldLength;
        return "正文 SHA-256 由 " + shortHash(oldHash) + " 变为 " + shortHash(newHash)
                + "；正文长度由 " + oldLength + " 变为 " + newLength
                + "（" + (delta >= 0 ? "+" : "") + delta + " 字）";
    }

    private static String shortHash(String value) {
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String nullable(Object value) {
        String result = text(value);
        return result.isBlank() ? null : result;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static String safeMessage(RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return fallback;
        int detail = message.indexOf("\"detail\":");
        if (detail < 0) return fallback;
        String sanitized = message.substring(detail + 9).replaceAll("[{}\"\\\\]", "").trim();
        return sanitized.isBlank() ? fallback : sanitized;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String abbreviate(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, max) + "…";
    }

    private void log(AuthUser user, String action, long targetId, String result) {
        jdbc.update("INSERT INTO operation_log(operator_id,organization_id,action,target_type,target_id,result,ip) "
                        + "VALUES (?,?,?,'SOURCE_DOCUMENT',?,?,'local')",
                user.id(), user.organizationId(), action, targetId, result);
    }

    private void assertAccess(long documentId, AuthUser user) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_document WHERE id=? AND source_type='WEB_ARTICLE'",
                Integer.class, documentId);
        if (exists == null || exists == 0) {
            throw new BusinessException(404, "网页文章不存在");
        }
        if (!user.isPlatformAdmin()) {
            Integer accessible = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM source_document WHERE id=? AND organization_id=?",
                    Integer.class, documentId, user.organizationId());
            if (accessible == null || accessible == 0) {
                throw new BusinessException(403, "当前机构无权访问该网页文章");
            }
        }
    }

    private record CachedPreview(Instant expiresAt, Map<String, Object> value) {}
}
