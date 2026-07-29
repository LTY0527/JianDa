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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AssistantService.class);
    private static final String DISCLAIMER = "仅帮助理解，正式要求以原文为准。涉及医疗、金融或政策决定时，请向主管部门或专业人员核实。";
    private static final int MAX_CITATIONS = 3;
    private final JdbcTemplate jdbc;
    private final AiClient aiClient;
    private final boolean externalEnabled;
    private final int dailyCallLimit;
    private final int dailyTokenLimit;

    public AssistantService(
            JdbcTemplate jdbc,
            AiClient aiClient,
            @Value("${jianda.assistant.external-enabled:false}") boolean externalEnabled,
            @Value("${jianda.assistant.daily-call-limit:30}") int dailyCallLimit,
            @Value("${jianda.assistant.daily-token-limit:30000}") int dailyTokenLimit) {
        this.jdbc = jdbc;
        this.aiClient = aiClient;
        this.externalEnabled = externalEnabled;
        this.dailyCallLimit = dailyCallLimit;
        this.dailyTokenLimit = dailyTokenLimit;
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

    public Map<String, Object> chat(String message, String contextSlug) {
        String question = message == null ? "" : message.trim();
        if (question.isBlank()) {
            return retrievalResponse("请先输入您想了解的问题。", List.of());
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.slug,p.title,p.summary,p.category,p.source_name,p.published_at,d.raw_text "
                        + "FROM published_item p JOIN source_document d ON d.id=p.document_id "
                        + "WHERE p.status='PUBLISHED' ORDER BY p.published_at DESC,p.id DESC");
        Set<String> terms = terms(question);
        List<RankedItem> ranked = rows.stream()
                .map(row -> new RankedItem(row, score(row, terms, contextSlug)))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingInt(RankedItem::score).reversed()
                        .thenComparing(item -> String.valueOf(item.row().get("published_at")), Comparator.reverseOrder())
                        .thenComparing(item -> String.valueOf(item.row().get("slug"))))
                .limit(MAX_CITATIONS)
                .toList();

        if (ranked.isEmpty()) {
            recordEvent(question, contextSlug, "retrieval", 0, 0, true,
                    null, null, 0, 0, 0, 0, "NO_EVIDENCE");
            return retrievalResponse("当前已发布内容中没有可靠答案。", List.of());
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
        if (!externalEnabled || !withinDailyBudget()) {
            recordEvent(question, contextSlug, "retrieval", citations.size(), citations.size(),
                    true, null, null, 0, 0, 0, 0,
                    externalEnabled ? "BUDGET_LIMIT" : "EXTERNAL_DISABLED");
            return retrievalResponse(answer, citations);
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
            if (generatedAnswer.isBlank() || usedCitations.isEmpty()) {
                throw new IllegalStateException("assistant response missing citations");
            }
            int promptTokens = number(generated.get("prompt_tokens"));
            int completionTokens = number(generated.get("completion_tokens"));
            int totalTokens = number(generated.get("total_tokens"));
            long elapsed = number(generated.get("elapsed_ms"));
            if (elapsed <= 0) elapsed = elapsedMs(started);
            recordEvent(question, contextSlug, "ai", citations.size(), usedCitations.size(),
                    true, text(generated, "model"), text(generated, "request_id"),
                    promptTokens, completionTokens, totalTokens, elapsed, null);
            Map<String, Object> response = response(generatedAnswer, usedCitations, "ai");
            response.put("actions", stringList(generated.get("actions")));
            return response;
        } catch (RuntimeException exception) {
            long elapsed = elapsedMs(started);
            recordEvent(question, contextSlug, "retrieval", citations.size(), citations.size(),
                    false, null, null, 0, 0, 0, elapsed, "EXTERNAL_FALLBACK");
            LOGGER.warn("assistant_rag_fallback category={} evidence_count={} elapsed_ms={} error_type={}",
                    questionCategory(question), citations.size(), elapsed,
                    exception.getClass().getSimpleName());
            return retrievalResponse(answer, citations);
        }
    }

    private Map<String, Object> retrievalResponse(
            String answer, List<Map<String, Object>> citations) {
        return response(answer, citations, "retrieval");
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

    private boolean withinDailyBudget() {
        Map<String, Object> usage = jdbc.queryForMap(
                "SELECT COUNT(*) call_count,COALESCE(SUM(total_tokens),0) token_count "
                        + "FROM assistant_query_event WHERE mode='ai' AND created_at>=CURRENT_DATE");
        return number(usage.get("call_count")) < dailyCallLimit
                && number(usage.get("token_count")) < dailyTokenLimit;
    }

    private void recordEvent(
            String question, String contextSlug, String mode, int evidenceCount,
            int citationCount, boolean success, String model, String requestId,
            int promptTokens, int completionTokens, int totalTokens, long durationMs,
            String errorCode) {
        jdbc.update(
                "INSERT INTO assistant_query_event(question_category,context_slug,mode,evidence_count,"
                        + "citation_count,success,model_id,request_id,prompt_tokens,completion_tokens,"
                        + "total_tokens,duration_ms,error_code) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                questionCategory(question), blankToNull(contextSlug), mode, evidenceCount,
                citationCount, success, blankToNull(model), blankToNull(requestId),
                promptTokens, completionTokens, totalTokens, durationMs, errorCode);
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

    private int score(Map<String, Object> row, Set<String> terms, String contextSlug) {
        String slug = text(row, "slug");
        int score = contextSlug != null && contextSlug.equals(slug) ? 100 : 0;
        String title = normalize(text(row, "title"));
        String category = normalize(text(row, "category"));
        String summary = normalize(text(row, "summary"));
        String raw = normalize(text(row, "raw_text"));
        for (String term : terms) {
            if (term.length() < 2) continue;
            if (category.contains(term) || term.contains(category)) score += 12;
            if (title.contains(term)) score += 8;
            if (summary.contains(term)) score += 4;
            if (raw.contains(term)) score += 2;
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
        result.put("quote", bestQuote(text(row, "raw_text"), text(row, "summary"), terms));
        return result;
    }

    private String bestQuote(String rawText, String summary, Set<String> terms) {
        String source = rawText.isBlank() ? summary : rawText;
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.CHINA);
        iterator.setText(source);
        int start = iterator.first();
        String fallback = source;
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = source.substring(start, end).trim();
            if (terms.stream().anyMatch(term -> term.length() >= 2 && normalize(sentence).contains(term))) {
                return shorten(sentence);
            }
            if (fallback.equals(source) && !sentence.isBlank()) fallback = sentence;
        }
        return shorten(fallback);
    }

    private String shorten(String value) {
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 140 ? compact : compact.substring(0, 137) + "…";
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
        return result;
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
