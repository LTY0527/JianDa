package cn.jianda.publicapi;

import java.util.List;
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
    public List<Map<String, Object>> publishedContent() {
        return jdbc.queryForList(
                "SELECT p.slug,p.title,p.summary,p.category,p.source_name,p.published_at,d.raw_text "
                        + "FROM published_item p JOIN source_document d ON d.id=p.document_id "
                        + "WHERE p.status='PUBLISHED' ORDER BY p.published_at DESC,p.id DESC");
    }
}
