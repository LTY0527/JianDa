package cn.jianda.publicapi;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {
    private static final String DISCLAIMER = "仅帮助理解，正式要求以原文为准。涉及医疗、金融或政策决定时，请向主管部门或专业人员核实。";
    private static final int MAX_CITATIONS = 3;
    private final JdbcTemplate jdbc;

    public AssistantService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
            return response("请先输入您想了解的问题。", List.of());
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
            return response("在当前已审核发布的内容中，没有找到足够可靠的依据。建议换一种说法，或查看相关部门原文后再作决定。", List.of());
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
        return response(answer, citations);
    }

    private Map<String, Object> response(String answer, List<Map<String, Object>> citations) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("citations", citations);
        result.put("disclaimer", DISCLAIMER);
        result.put("mode", "retrieval");
        return result;
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
