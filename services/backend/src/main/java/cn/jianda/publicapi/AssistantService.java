package cn.jianda.publicapi;

import cn.jianda.ai.AiClient;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AssistantService.class);
    private static final String DISCLAIMER = "仅帮助理解，正式要求以原文为准。涉及医疗、金融或政策决定时，请向主管部门或专业人员核实。";
    private static final String COMMUNITY_DISCLAIMER = "邻里信息由居民发布，未经官方核验，不作为政策、办事或其他官方依据，请自行联系确认并注意安全。";
    private static final String DEFAULT_REGION = SupportedRegions.DEFAULT_CODE;
    private static final int MAX_CITATIONS = 3;
    private static final int MAX_COMMUNITY_POSTS = 5;
    private static final List<Pattern> GROUNDED_FACT_PATTERNS = List.of(
            Pattern.compile("\\d{4}年\\d{1,2}月\\d{1,2}日|\\d{1,2}月\\d{1,2}日"),
            Pattern.compile("\\d{1,2}[:：]\\d{2}(?:\\s*[-—至]\\s*\\d{1,2}[:：]\\d{2})?"),
            Pattern.compile("(?:0\\d{2,3}[-— ]?)?\\d{7,8}"),
            Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:元|万元|块)"));
    private final JdbcTemplate jdbc;
    private final AiClient aiClient;
    private final PublishedContentRetriever retriever;
    private final WebSearchProvider webSearchProvider;
    private final boolean externalEnabled;

    public AssistantService(
            JdbcTemplate jdbc,
            AiClient aiClient,
            PublishedContentRetriever retriever,
            WebSearchProvider webSearchProvider,
            @Value("${jianda.assistant.external-enabled:false}") boolean externalEnabled) {
        this.jdbc = jdbc;
        this.aiClient = aiClient;
        this.retriever = retriever;
        this.webSearchProvider = webSearchProvider;
        this.externalEnabled = externalEnabled;
    }

    public List<String> suggestions() {
        List<String> categories = jdbc.queryForList(
                "SELECT DISTINCT category FROM published_item WHERE status='PUBLISHED' ORDER BY category",
                String.class);
        List<String> result = new ArrayList<>();
        if (categories.contains("反诈")) result.add("最近有哪些反诈提醒？");
        if (categories.contains("养老")) result.add("有哪些适合老年人的办事指南？");
        if (categories.contains("健康")) result.add("最近有哪些健康提醒？");
        if (categories.contains("生活服务")) result.add("生活服务事项需要准备什么？");
        if (result.isEmpty()) result.add("平台最近发布了哪些重要内容？");
        return result.stream().limit(4).toList();
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retrieval", "ready");
        WebSearchProvider.Status webSearch = webSearchProvider.status();
        result.put("webSearch", Map.of(
                "provider", webSearch.provider(),
                "status", webSearch.state(),
                "message", webSearch.message()));
        if (!externalEnabled) {
            result.put("status", "disabled");
            result.put("external", "disabled");
            return result;
        }
        try {
            Map<String, Object> upstream = aiClient.assistantStatus();
            String upstreamStatus = text(upstream, "status");
            String status = Set.of("ready", "degraded", "disabled").contains(upstreamStatus)
                    ? upstreamStatus : "degraded";
            result.put("status", status);
            result.put("external", status);
        } catch (RuntimeException exception) {
            result.put("status", "unreachable");
            result.put("external", "unreachable");
        }
        return result;
    }

    public Map<String, Object> chat(String message, String contextSlug) {
        return chat(message, contextSlug, DEFAULT_REGION, null, null);
    }

    public Map<String, Object> chat(String message, String contextSlug, String regionCode) {
        return chat(message, contextSlug, regionCode, null, null);
    }

    public Map<String, Object> chat(String message, String contextSlug, String regionCode,
                                    Long residentUserId, String visitorId) {
        String question = message == null ? "" : message.trim();
        if (question.isBlank()) {
            return retrievalResponse("请先输入您想了解的问题。", List.of(), null);
        }
        if (isCommunityQuestion(question)) {
            return communityResponse(question, regionCode, residentUserId, visitorId);
        }
        if (isStatusQuestion(question)) {
            Map<String, Object> runtime = status();
            String runtimeStatus = text(runtime, "status");
            recordEvent(question, contextSlug, "status", 0, 0, true,
                    null, null, 0, 0, 0, 0, null, residentUserId, visitorId);
            String detail = switch (runtimeStatus) {
                case "ready" -> "AI 整理能力可用。";
                case "degraded" -> "AI 整理配置不完整，当前降级为原文检索。";
                case "unreachable" -> "AI 服务暂时无法连接，当前降级为原文检索。";
                default -> "AI 整理能力未启用，当前使用原文检索。";
            };
            Map<String, Object> response = response(
                    "简达助手的已审核内容检索可用。" + detail, List.of(), "status");
            response.put("assistantStatus", runtimeStatus);
            return response;
        }

        String evidenceRegion = SupportedRegions.mentionedIn(question)
                .map(SupportedRegions.Region::code)
                .orElse(SupportedRegions.require(regionCode).code());
        List<Map<String, Object>> rows = retriever.publishedContent(evidenceRegion);
        Set<String> terms = terms(question);
        Set<String> anchors = queryAnchors(question);
        List<RankedItem> ranked = rows.stream()
                .map(row -> new RankedItem(row, score(row, terms, anchors, contextSlug)))
                .filter(item -> item.score() > 0)
                .filter(item -> !requiresGroundedEvidence(question)
                        || supportsGroundedIntent(item.row(), question))
                .sorted(Comparator.comparingInt(RankedItem::score).reversed()
                        .thenComparing(item -> String.valueOf(item.row().get("published_at")), Comparator.reverseOrder())
                        .thenComparing(item -> String.valueOf(item.row().get("slug"))))
                .limit(MAX_CITATIONS)
                .toList();

        if (ranked.isEmpty()) {
            if (!requiresGroundedEvidence(question) && externalEnabled) {
                if (webSearchProvider.status().ready()) {
                    Map<String, Object> webAnswer = webAiResponse(
                            question, contextSlug, residentUserId, visitorId);
                    if (webAnswer != null) return webAnswer;
                }
                return generalAiResponse(question, contextSlug, residentUserId, visitorId);
            }
            String grounded = requiresGroundedEvidence(question) ? "NO_EVIDENCE" : (externalEnabled ? "AI_DISABLED" : "NO_EVIDENCE");
            recordEvent(question, contextSlug, "retrieval", 0, 0, true,
                    null, null, 0, 0, 0, 0, grounded, residentUserId, visitorId);
            String fallbackText = requiresGroundedEvidence(question)
                    ? "当前已审核发布内容中没有可靠依据。这个问题涉及资格、金额、材料、医疗或其他重要决定，简达不会猜测，请查阅主管部门原文或向工作人员核实。"
                    : (externalEnabled ? "当前已审核发布内容中没有可靠答案，通用 AI 当前不可用。" : "当前已审核发布内容中没有可靠答案，AI 整理能力未启用。");
            return retrievalResponse(fallbackText, List.of(), grounded);
        }

        List<Map<String, Object>> citations = ranked.stream()
                .map(item -> citation(item.row(), terms))
                .toList();
        String titles = ranked.stream()
                .map(item -> "《" + item.row().get("title") + "》")
                .reduce((left, right) -> left + "、" + right)
                .orElse("");
        String answer = "根据平台已审核发布的内容，您可以先查看" + titles
                + "。下方列出了与问题最相关的原文片段，请结合完整原文确认适用条件、材料和时限。";
        if (!externalEnabled) {
            recordEvent(question, contextSlug, "retrieval", citations.size(), citations.size(),
                    true, null, null, 0, 0, 0, 0, "AI_DISABLED",
                    residentUserId, visitorId);
            return retrievalResponse(answer, citations, "AI_DISABLED");
        }
        long started = System.nanoTime();
        try {
            List<Map<String, Object>> evidence = new ArrayList<>();
            for (int index = 0; index < citations.size(); index++) {
                Map<String, Object> citation = citations.get(index);
                evidence.add(Map.of(
                        "index", index + 1,
                        "title", citation.get("title"),
                        "slug", citation.get("slug"),
                        "source_name", citation.get("sourceName"),
                        "quote", citation.get("quote")));
            }
            Map<String, Object> generated = aiClient.answerAssistant(question, evidence);
            List<Integer> used = integerList(generated.get("used_citation_indexes"));
            List<Map<String, Object>> usedCitations = new ArrayList<>();
            for (Integer index : used) {
                if (index != null && index >= 1 && index <= citations.size()) {
                    usedCitations.add(citations.get(index - 1));
                }
            }
            String generatedAnswer = text(generated, "answer").trim();
            // ====== 弱回答兜底 web_ai（本地RAG弱命中时，再试官方联网来源） ======
            if (webSearchProvider.status().ready() && isWeakRagAnswer(generatedAnswer)) {
                Map<String, Object> webAnswer = webAiResponse(
                        question, contextSlug, residentUserId, visitorId);
                if (webAnswer != null) return webAnswer;
            }
            if (generatedAnswer.isBlank() || usedCitations.isEmpty()) {
                throw new IllegalStateException("assistant response missing citations");
            }
            if (!factsCoveredByEvidence(generatedAnswer, usedCitations)) {
                throw new IllegalStateException("assistant response contains unsupported factual value");
            }
            int promptTokens = number(generated.get("prompt_tokens"));
            int completionTokens = number(generated.get("completion_tokens"));
            int totalTokens = number(generated.get("total_tokens"));
            long elapsed = number(generated.get("elapsed_ms"));
            if (elapsed <= 0) elapsed = elapsedMs(started);
            recordEvent(question, contextSlug, "ai", citations.size(), usedCitations.size(),
                    true, text(generated, "model"), text(generated, "request_id"),
                    promptTokens, completionTokens, totalTokens, elapsed, null, residentUserId, visitorId);
            Map<String, Object> response = response(generatedAnswer, usedCitations, "ai");
            response.put("actions", stringList(generated.get("actions")));
            response.put("factCards", factCards(ranked, used));
            String quality = text(generated, "answer_quality");
            if (!quality.isBlank()) response.put("answerQuality", quality);
            return response;
        } catch (RuntimeException exception) {
            long elapsed = elapsedMs(started);
            // ====== RAG AI 异常时也兜底 web_ai ======
            if (webSearchProvider.status().ready()) {
                Map<String, Object> webAnswer = webAiResponse(
                        question, contextSlug, residentUserId, visitorId);
                if (webAnswer != null) return webAnswer;
            }
            recordEvent(question, contextSlug, "retrieval", citations.size(), citations.size(),
                    false, null, null, 0, 0, 0, elapsed, "EXTERNAL_FALLBACK", residentUserId, visitorId);
            LOGGER.warn("assistant_rag_fallback category={} evidence_count={} elapsed_ms={} error_type={}",
                    questionCategory(question), citations.size(), elapsed,
                    exception.getClass().getSimpleName());
            Map<String, Object> resp = retrievalResponse(answer, citations, "EXTERNAL_FALLBACK");
            resp.put("aiErrorHint", "外部 AI 调用暂时失败，已自动降级为原文检索。可稍后重试。");
            return resp;
        }
    }

    /**
     * 判断 RAG AI 合成回答是否属于"弱回答"——当本地证据与问题弱匹配但实际无法正面回答时，
     * 应降级去尝试联网官方来源。条件：回答长度较短 且 包含明确的失败语义关键词。
     */
    private static boolean isWeakRagAnswer(String answer) {
        if (answer == null) return true;
        if (answer.isBlank()) return true;
        if (answer.length() >= 180) return false;
        String normalized = answer.toLowerCase(Locale.ROOT);
        return List.of("未提及", "未能提供", "没有找到", "无法回答", "没有明确",
                "证据未涉及", "证据仅涉及", "未在证据中", "未给出具体", "不涉及",
                "未包含", "无法确认", "未找到", "没有相关", "不包含", "证据不足",
                "未提供", "没有包含", "未涉及", "无法提供", "未收录", "未查询到",
                "没有可靠", "没有依据", "找不到相关").stream()
                .anyMatch(normalized::contains);
    }

    private Map<String, Object> communityResponse(String question, String regionCode,
                                                  Long residentUserId, String visitorId) {
        String activeRegion = SupportedRegions.mentionedIn(question)
                .map(SupportedRegions.Region::code)
                .orElse(SupportedRegions.require(regionCode).code());
        Set<String> terms = terms(question);
        List<Map<String, Object>> posts = jdbc.queryForList(
                "SELECT p.id,p.category,p.content,p.region_code,p.district,p.street_or_town,p.created_at,u.nickname "
                        + "FROM community_post p JOIN resident_user u ON u.id=p.resident_user_id "
                        + "WHERE p.status='VISIBLE' AND p.region_code=? "
                        + "ORDER BY p.created_at DESC,p.id DESC LIMIT 50",
                activeRegion);
        List<Map<String, Object>> matches = posts.stream()
                .map(post -> new RankedItem(post, communityScore(post, terms)))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingInt(RankedItem::score).reversed()
                        .thenComparing(item -> String.valueOf(item.row().get("created_at")), Comparator.reverseOrder())
                        .thenComparing(item -> number(item.row().get("id")), Comparator.reverseOrder()))
                .limit(MAX_COMMUNITY_POSTS)
                .map(RankedItem::row)
                .toList();
        String answer = matches.isEmpty()
                ? "当前地区的可见邻里信息中，没有找到与这个问题相关的帖子。"
                : "在当前地区找到 " + matches.size()
                        + " 条相关邻里信息。以下内容由居民发布，请查看发布时间并自行联系确认。";
        recordEvent(question, null, "community_post", matches.size(), 0, true,
                null, null, 0, 0, 0, 0, matches.isEmpty() ? "NO_COMMUNITY_POST" : null,
                residentUserId, visitorId);
        Map<String, Object> result = response(answer, List.of(), "community_post");
        result.put("communityPosts", matches);
        result.put("disclaimer", COMMUNITY_DISCLAIMER);
        return result;
    }

    private int communityScore(Map<String, Object> post, Set<String> terms) {
        String content = normalize(text(post, "content"));
        String category = normalize(text(post, "category"));
        int score = 0;
        for (String term : terms) {
            if (term.length() < 2) continue;
            if (content.contains(term)) score += 8;
            if (category.contains(term) || term.contains(category)) score += 4;
        }
        return score;
    }

    private boolean isCommunityQuestion(String question) {
        String normalized = normalize(question);
        boolean neighborhood = List.of("邻里", "邻居", "社区帖子", "居民发布", "互助信息", "拼车", "搭子")
                .stream().anyMatch(normalized::contains);
        boolean localActivity = normalized.contains("附近")
                && List.of("活动", "互助", "闲置", "求助").stream().anyMatch(normalized::contains);
        return neighborhood || localActivity;
    }

    private Map<String, Object> retrievalResponse(
            String answer, List<Map<String, Object>> citations, String errorCode) {
        Map<String, Object> result = response(answer, citations, "retrieval");
        if (errorCode != null && !errorCode.isBlank()) result.put("errorCode", errorCode);
        return result;
    }

    private Map<String, Object> response(
            String answer, List<Map<String, Object>> citations, String mode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("citations", citations);
        result.put("disclaimer", DISCLAIMER);
        result.put("mode", mode);
        return result;
    }

    private boolean factsCoveredByEvidence(
            String answer, List<Map<String, Object>> citations) {
        String evidence = citations.stream().map(item -> text(item, "quote"))
                .reduce((left, right) -> left + " " + right).orElse("");
        String normalizedEvidence = normalizeFact(evidence);
        for (Pattern pattern : GROUNDED_FACT_PATTERNS) {
            Matcher matcher = pattern.matcher(answer);
            while (matcher.find()) {
                if (!normalizedEvidence.contains(normalizeFact(matcher.group()))) return false;
            }
        }
        return true;
    }

    private List<Map<String, Object>> factCards(List<RankedItem> ranked, List<Integer> usedIndexes) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Integer usedIndex : usedIndexes) {
            if (usedIndex == null || usedIndex < 1 || usedIndex > ranked.size()) continue;
            Object documentId = ranked.get(usedIndex - 1).row().get("document_id");
            if (!(documentId instanceof Number number)) continue;
            List<Map<String, Object>> fields = jdbc.queryForList(
                    "SELECT field_type,field_label,field_value FROM extracted_field WHERE document_id=? "
                            + "AND review_status IN ('CONFIRMED','MODIFIED') "
                            + "AND field_type IN ('START_DATE','END_DATE','LOCATION','CONTACT','FEE','MATERIAL') "
                            + "ORDER BY id",
                    number.longValue());
            for (Map<String, Object> field : fields) {
                String type = text(field, "field_type");
                String value = text(field, "field_value").trim();
                if (value.isBlank() || !seen.add(type + "\u0000" + value)) continue;
                result.add(Map.of(
                        "type", switch (type) {
                            case "START_DATE", "END_DATE" -> "deadline";
                            case "LOCATION" -> "location";
                            case "CONTACT" -> "phone";
                            case "FEE" -> "fee";
                            case "MATERIAL" -> "material";
                            default -> "fact";
                        },
                        "label", text(field, "field_label"),
                        "value", value));
                if (result.size() >= 6) return result;
            }
        }
        return result;
    }

    static String normalizeFact(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", "").replace('：', ':');
        Matcher dates = Pattern.compile("(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})日?")
                .matcher(normalized);
        StringBuffer canonical = new StringBuffer();
        while (dates.find()) {
            String replacement = String.format(
                    Locale.ROOT, "%04d%02d%02d",
                    Integer.parseInt(dates.group(1)),
                    Integer.parseInt(dates.group(2)),
                    Integer.parseInt(dates.group(3)));
            dates.appendReplacement(canonical, replacement);
        }
        dates.appendTail(canonical);
        return canonical.toString().replaceAll("[—–－-]+", "");
    }

    private Map<String, Object> generalAiResponse(String question, String contextSlug,
                                                  Long residentUserId, String visitorId) {
        long started = System.nanoTime();
        try {
            Map<String, Object> generated = aiClient.answerGeneralAssistant(question);
            String answer = text(generated, "answer").trim();
            if (answer.isBlank()) throw new IllegalStateException("general assistant answer is empty");
            int promptTokens = number(generated.get("prompt_tokens"));
            int completionTokens = number(generated.get("completion_tokens"));
            int totalTokens = number(generated.get("total_tokens"));
            long elapsed = number(generated.get("elapsed_ms"));
            if (elapsed <= 0) elapsed = elapsedMs(started);
            recordEvent(question, contextSlug, "general_ai", 0, 0, true,
                    text(generated, "model"), text(generated, "request_id"),
                    promptTokens, completionTokens, totalTokens, elapsed, null,
                    residentUserId, visitorId);
            Map<String, Object> result = response(answer, List.of(), "general_ai");
            result.put("actions", stringList(generated.get("actions")));
            String quality = text(generated, "answer_quality");
            if (!quality.isBlank()) result.put("answerQuality", quality);
            return result;
        } catch (RuntimeException exception) {
            long elapsed = elapsedMs(started);
            recordEvent(question, contextSlug, "retrieval", 0, 0, false,
                    null, null, 0, 0, 0, elapsed, "EXTERNAL_FALLBACK",
                    residentUserId, visitorId);
            LOGGER.warn("assistant_general_fallback category={} elapsed_ms={} error_type={}",
                    questionCategory(question), elapsed, exception.getClass().getSimpleName());
            Map<String, Object> resp = retrievalResponse(
                    "当前已审核发布内容中没有可靠答案，通用 AI 暂时不可用。", List.of(),
                    "EXTERNAL_FALLBACK");
            resp.put("aiErrorHint", "外部 AI 调用暂时失败，已自动降级。可稍后重试。");
            return resp;
        }
    }

    private Map<String, Object> webAiResponse(String question, String contextSlug,
                                               Long residentUserId, String visitorId) {
        long started = System.nanoTime();
        try {
            List<WebSearchProvider.SearchResult> results = webSearchProvider.search(question, MAX_CITATIONS);
            if (results.isEmpty()) return null;
            List<Map<String, Object>> citations = new ArrayList<>();
            List<Map<String, Object>> evidence = new ArrayList<>();
            for (int index = 0; index < results.size(); index++) {
                WebSearchProvider.SearchResult item = results.get(index);
                Map<String, Object> citation = new LinkedHashMap<>();
                citation.put("title", item.title());
                citation.put("kind", "external");
                citation.put("category", "联网资料");
                citation.put("sourceName", item.sourceName());
                citation.put("publishedAt", "");
                citation.put("quote", item.snippet());
                citation.put("url", item.url());
                citations.add(citation);
                evidence.add(Map.of(
                        "index", index + 1,
                        "title", item.title(),
                        "slug", item.url(),
                        "source_name", item.sourceName(),
                        "quote", item.snippet()));
            }
            Map<String, Object> generated = aiClient.answerAssistant(question, evidence);
            List<Integer> used = integerList(generated.get("used_citation_indexes"));
            List<Map<String, Object>> usedCitations = new ArrayList<>();
            for (Integer index : used) {
                if (index != null && index >= 1 && index <= citations.size()) {
                    usedCitations.add(citations.get(index - 1));
                }
            }
            String answer = text(generated, "answer").trim();
            if (answer.isBlank() || usedCitations.isEmpty()
                    || !factsCoveredByEvidence(answer, usedCitations)) {
                throw new IllegalStateException("web assistant response is not grounded");
            }
            int promptTokens = number(generated.get("prompt_tokens"));
            int completionTokens = number(generated.get("completion_tokens"));
            int totalTokens = number(generated.get("total_tokens"));
            long elapsed = number(generated.get("elapsed_ms"));
            if (elapsed <= 0) elapsed = elapsedMs(started);
            recordEvent(question, contextSlug, "web_ai", citations.size(), usedCitations.size(),
                    true, text(generated, "model"), text(generated, "request_id"),
                    promptTokens, completionTokens, totalTokens, elapsed, null,
                    residentUserId, visitorId);
            Map<String, Object> response = response(answer, usedCitations, "web_ai");
            response.put("actions", stringList(generated.get("actions")));
            response.put("webSearchProvider", webSearchProvider.status().provider());
            String quality = text(generated, "answer_quality");
            if (!quality.isBlank()) response.put("answerQuality", quality);
            return response;
        } catch (RuntimeException exception) {
            LOGGER.warn("assistant_web_search_fallback category={} elapsed_ms={} error_type={}",
                    questionCategory(question), elapsedMs(started), exception.getClass().getSimpleName());
            return null;
        }
    }

    private void recordEvent(
            String question, String contextSlug, String mode, int evidenceCount,
            int citationCount, boolean success, String model, String requestId,
            int promptTokens, int completionTokens, int totalTokens, long durationMs,
            String errorCode, Long residentUserId, String visitorId) {
        jdbc.update(
                "INSERT INTO assistant_query_event(question_category,context_slug,mode,evidence_count,"
                        + "citation_count,success,model_id,request_id,prompt_tokens,completion_tokens,"
                        + "total_tokens,duration_ms,error_code,resident_user_id,visitor_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                questionCategory(question), blankToNull(contextSlug), mode, evidenceCount,
                citationCount, success, blankToNull(model), blankToNull(requestId),
                promptTokens, completionTokens, totalTokens, durationMs, errorCode,
                residentUserId, blankToNull(visitorId));
    }

    private String questionCategory(String question) {
        String normalized = normalize(question);
        for (String category : List.of("反诈", "健康", "养老", "办事", "费用", "材料", "地点", "时间")) {
            if (normalized.contains(category)) return category;
        }
        return "其他";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static List<Integer> integerList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Number.class::isInstance)
                .map(Number.class::cast).map(Number::intValue).toList();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).map(String::trim)
                .filter(item -> !item.isBlank()).limit(3).toList();
    }

    private int score(Map<String, Object> row, Set<String> terms, Set<String> anchors, String contextSlug) {
        String slug = text(row, "slug");
        int score = contextSlug != null && contextSlug.equals(slug) ? 100 : 0;
        String title = normalize(text(row, "title"));
        String category = normalize(text(row, "category"));
        String summary = normalize(text(row, "summary"));
        String raw = normalize(text(row, "raw_text"));
        String verifiedFacts = normalize(text(row, "verified_facts"));
        boolean contextual = contextSlug != null && contextSlug.equals(slug);
        boolean anchorMatched = anchors.stream().anyMatch(anchor ->
                title.contains(anchor) || category.contains(anchor) || summary.contains(anchor)
                        || verifiedFacts.contains(anchor));
        if (!contextual && !anchorMatched) return 0;
        for (String term : terms) {
            if (term.length() < 2) continue;
            if (category.contains(term) || term.contains(category)) score += 12;
            if (title.contains(term)) score += 8;
            if (summary.contains(term)) score += 4;
            if (raw.contains(term)) score += 2;
            if (verifiedFacts.contains(term)) score += 10;
        }
        return score;
    }

    private Map<String, Object> citation(Map<String, Object> row, Set<String> terms) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", row.get("title"));
        result.put("slug", row.get("slug"));
        result.put("kind", isGuide(text(row, "category")) ? "guide" : "news");
        result.put("category", row.get("category"));
        result.put("sourceName", row.get("source_name"));
        result.put("publishedAt", row.get("published_at"));
        result.put("quote", bestQuote(
                text(row, "raw_text"), text(row, "summary"),
                text(row, "verified_facts"), terms));
        return result;
    }

    private String bestQuote(
            String rawText, String summary, String verifiedFacts, Set<String> terms) {
        String source = rawText.isBlank() ? summary : rawText;
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.CHINA);
        iterator.setText(source);
        int start = iterator.first();
        String fallback = source;
        List<String> matches = new ArrayList<>();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = source.substring(start, end).trim();
            if (terms.stream().anyMatch(term -> term.length() >= 2 && normalize(sentence).contains(term))) {
                matches.add(sentence);
                if (matches.size() >= 4) break;
            }
            if (fallback.equals(source) && !sentence.isBlank()) fallback = sentence;
        }
        List<String> evidence = new ArrayList<>();
        if (!verifiedFacts.isBlank()) evidence.add("已审核字段：" + verifiedFacts);
        if (!matches.isEmpty()) evidence.addAll(matches);
        else if (!fallback.isBlank()) evidence.add(fallback);
        if (!summary.isBlank()) evidence.add("审核摘要：" + summary);
        return shorten(String.join(" ", evidence), 500);
    }

    private String shorten(String value) {
        return shorten(value, 140);
    }

    private String shorten(String value, int maxLength) {
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= maxLength
                ? compact : compact.substring(0, Math.max(0, maxLength - 3)) + "…";
    }

    private Set<String> terms(String value) {
        String normalized = normalize(value);
        Set<String> result = new LinkedHashSet<>();
        for (String token : normalized.split("[的了和与及是有哪些什么怎么如何最近相关需要可以请问一下]+")) {
            if (token.length() >= 2) result.add(token);
        }
        for (String category : List.of("时政", "健康", "养老", "反诈", "生活服务", "文化", "办事", "材料", "地点", "费用", "时间")) {
            if (normalized.contains(category)) result.add(category);
        }
        expandSynonyms(normalized, result);
        return result;
    }

    static Set<String> queryAnchors(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]+", "");
        Set<String> result = new LinkedHashSet<>();
        for (String token : normalized.split("[的了和与及是有哪些什么怎么如何最近相关需要可以请问一下]+")) {
            if (token.length() >= 2) result.add(token);
        }
        List<List<String>> topics = List.of(
                List.of("健康", "卫生", "医疗", "体检", "疫苗"),
                List.of("养老", "老年", "老人", "银龄", "长者", "助餐"),
                List.of("防诈", "诈骗", "反诈"),
                List.of("办事", "办理", "申请", "申办"));
        for (List<String> group : topics) {
            if (group.stream().anyMatch(normalized::contains)) result.addAll(group);
        }
        return result;
    }

    private void expandSynonyms(String normalized, Set<String> result) {
        List<List<String>> groups = List.of(
                List.of("办理", "申请", "申办"),
                List.of("材料", "证件", "资料"),
                List.of("时间", "日期", "期限", "截止"),
                List.of("地点", "地址", "窗口"),
                List.of("电话", "联系方式", "咨询"),
                List.of("费用", "收费", "金额", "免费"),
                List.of("老年", "老人", "银龄", "长者"),
                List.of("防诈", "诈骗", "反诈"));
        for (List<String> group : groups) {
            if (group.stream().anyMatch(normalized::contains)) {
                result.addAll(group);
            }
        }
    }

    private boolean isStatusQuestion(String question) {
        String normalized = normalize(question);
        return List.of("运行状态", "服务状态", "助手状态", "运行正常吗", "助手正常吗", "系统正常吗", "能用吗")
                .stream().anyMatch(normalized::contains);
    }

    private boolean requiresGroundedEvidence(String question) {
        String normalized = normalize(question);
        return List.of(
                "诊断", "症状", "治疗", "用药", "吃药", "剂量",
                "资格", "符合条件", "能不能申请", "可以申请吗",
                "金额", "补贴多少", "费用多少", "收费多少",
                "补贴", "电话", "联系方式", "咨询电话",
                "办理材料", "申请材料", "需要什么材料", "带什么证件",
                "法律", "投资", "收益", "转账")
                .stream().anyMatch(normalized::contains);
    }

    private boolean supportsGroundedIntent(Map<String, Object> row, String question) {
        String normalizedQuestion = normalize(question);
        String evidence = normalize(String.join(" ",
                text(row, "title"), text(row, "category"), text(row, "summary"),
                text(row, "raw_text"), text(row, "verified_facts")));
        List<List<String>> intents = List.of(
                List.of("补贴", "津贴", "补助"),
                List.of("资格", "条件", "符合"),
                List.of("材料", "证件"),
                List.of("金额", "费用", "收费"),
                List.of("电话", "联系方式", "咨询电话"),
                List.of("诊断", "症状", "治疗", "用药", "吃药", "剂量"),
                List.of("法律", "投资", "收益", "转账"));
        boolean checked = false;
        for (List<String> intent : intents) {
            if (intent.stream().noneMatch(normalizedQuestion::contains)) continue;
            checked = true;
            if (intent.stream().noneMatch(evidence::contains)) return false;
        }
        return !checked || !evidence.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value.toString();
    }

    private boolean isGuide(String category) {
        return List.of("办事", "养老", "生活服务", "社会保障", "公共服务").stream().anyMatch(category::contains);
    }

    private record RankedItem(Map<String, Object> row, int score) {}

}
