package cn.jianda.publicapi;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPublishedContentRetriever implements PublishedContentRetriever {
    private final JdbcTemplate jdbc;

    public JdbcPublishedContentRetriever(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Map<String, Object>> publishedContent(String regionCode) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.document_id,p.slug,p.title,p.summary,p.category,p.source_name,p.published_at,"
                        + "p.region_code,p.local_scope,d.raw_text "
                        + "FROM published_item p JOIN source_document d ON d.id=p.document_id "
                        + "WHERE p.status='PUBLISHED' AND (p.region_code=? "
                        + "OR p.local_scope IN ('CITY','NATIONAL','CITY_SHARED','NATIONAL_SHARED','UNSPECIFIED')) "
                        + "ORDER BY CASE WHEN p.region_code=? THEN 0 ELSE 1 END,p.published_at DESC,p.id DESC",
                SupportedRegions.normalize(regionCode), SupportedRegions.normalize(regionCode));
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> enriched = new LinkedHashMap<>(row);
            long documentId = ((Number) row.get("document_id")).longValue();
            List<Map<String, Object>> fields = jdbc.queryForList(
                    "SELECT field_label,field_value,source_quote FROM extracted_field "
                            + "WHERE document_id=? AND review_status IN ('CONFIRMED','MODIFIED') ORDER BY id",
                    documentId);
            String verifiedFacts = fields.stream().limit(12)
                    .map(field -> String.valueOf(field.get("field_label")) + "："
                            + String.valueOf(field.get("field_value")) + "（原文："
                            + String.valueOf(field.get("source_quote")) + "）")
                    .reduce((left, right) -> left + "；" + right).orElse("");
            enriched.put("verified_facts", verifiedFacts);
            result.add(enriched);
        }
        return result;
    }
}
