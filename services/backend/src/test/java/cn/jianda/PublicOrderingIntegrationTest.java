package cn.jianda;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.not;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-public-ordering-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class PublicOrderingIntegrationTest {
    private static final String MARKER = "连续阅读排序测试";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepareItems() {
        jdbc.update("DELETE FROM published_item");
        jdbc.update("DELETE FROM source_document WHERE title LIKE ?", MARKER + "%");
        insert("ordering-test-pinned", "测试分类甲", true, 10, "2026-07-20T10:00:00", "PUBLISHED");
        insert("ordering-test-important", "测试分类乙", false, 90, "2026-07-24T10:00:00", "PUBLISHED");
        insert("ordering-test-same-newer-id", "测试分类甲", false, 50, "2026-07-23T10:00:00", "PUBLISHED");
        insert("ordering-test-same-older-id", "测试分类乙", false, 50, "2026-07-23T10:00:00", "PUBLISHED");
        insert("ordering-test-withdrawn", "测试分类甲", true, 100, "2026-07-25T10:00:00", "WITHDRAWN");
    }

    @Test
    void listAndSearchUseStableBusinessOrderAndExcludeWithdrawnItems() throws Exception {
        mvc.perform(get("/api/public/search").param("keyword", MARKER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].slug", contains(
                        "ordering-test-pinned",
                        "ordering-test-important",
                        "ordering-test-same-older-id",
                        "ordering-test-same-newer-id")));

        mvc.perform(get("/api/public/items").param("category", "测试分类甲"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].slug", contains(
                        "ordering-test-pinned",
                        "ordering-test-same-newer-id")));
    }

    @Test
    void neighborsFollowFullTupleAndFilterWithdrawnItems() throws Exception {
        mvc.perform(get("/api/public/items/ordering-test-important/neighbors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previous.slug").value("ordering-test-pinned"))
                .andExpect(jsonPath("$.data.next.slug").value("ordering-test-same-older-id"));

        mvc.perform(get("/api/public/items/ordering-test-same-older-id/neighbors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previous.slug").value("ordering-test-important"))
                .andExpect(jsonPath("$.data.next.slug").value("ordering-test-same-newer-id"));

        mvc.perform(get("/api/public/items/ordering-test-pinned/neighbors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previous").doesNotExist())
                .andExpect(jsonPath("$.data.next.slug").value("ordering-test-important"));

        mvc.perform(get("/api/public/items/ordering-test-same-newer-id/neighbors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previous.slug").value("ordering-test-same-older-id"))
                .andExpect(jsonPath("$.data.next").doesNotExist());
    }

    @Test
    void sameCategoryIsPreferredPerDirectionAndFallsBackGloballyAtBoundary() throws Exception {
        mvc.perform(get("/api/public/items/ordering-test-important/neighbors")
                        .param("sameCategory", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previous.slug").value("ordering-test-pinned"))
                .andExpect(jsonPath("$.data.next.slug").value("ordering-test-same-older-id"));

        mvc.perform(get("/api/public/items/ordering-test-same-newer-id/neighbors")
                        .param("sameCategory", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previous.slug").value("ordering-test-pinned"))
                .andExpect(jsonPath("$.data.next").doesNotExist());
    }

    @Test
    void dachangRegionIsExposedAndRegionMetadataIsReturned() throws Exception {
        jdbc.update("UPDATE published_item SET province='上海市',city='上海市',district='宝山区',"
                + "street_or_town='大场镇',region_code='310113102',local_scope='TOWN',is_local=TRUE "
                + "WHERE slug='ordering-test-pinned'");

        mvc.perform(get("/api/public/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].district").value("宝山区"))
                .andExpect(jsonPath("$.data[0].street_or_town").value("大场镇"))
                .andExpect(jsonPath("$.data[0].region_code").value("310113102"));
        mvc.perform(get("/api/public/items").param("regionCode", "310113102"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].street_or_town").value("大场镇"))
                .andExpect(jsonPath("$.data[0].region_code").value("310113102"));
    }

    @Test
    void expiredContentLeavesListsButDetailRemainsTraceable() throws Exception {
        jdbc.update("UPDATE published_item SET expires_at=DATEADD('DAY',-1,CURRENT_TIMESTAMP),"
                + "deadline_at=DATEADD('DAY',-2,CURRENT_TIMESTAMP) WHERE slug='ordering-test-important'");

        mvc.perform(get("/api/public/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].slug", not(hasItem("ordering-test-important"))));
        mvc.perform(get("/api/public/search").param("keyword", MARKER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].slug", not(hasItem("ordering-test-important"))));
        mvc.perform(get("/api/public/items/ordering-test-important"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("ordering-test-important"))
                .andExpect(jsonPath("$.data.expires_at").exists());
    }

    private void insert(String slug, String category, boolean pinned, int importance,
            String publishedAt, String status) {
        String title = MARKER + slug;
        jdbc.update("INSERT INTO source_document(organization_id,title,file_name,file_type,storage_path,raw_text,page_count,processing_status,created_by) "
                + "VALUES (1,?,NULL,'HTML',NULL,?,1,'PUBLISHED',1)", title, title);
        long documentId = jdbc.queryForObject("SELECT id FROM source_document WHERE title=?", Long.class, title);
        jdbc.update("INSERT INTO published_item(document_id,slug,title,summary,category,published_by,published_at,status,source_name,source_url,pinned,importance) "
                        + "VALUES (?,?,?,?,?,1,?,?, '测试权威来源','https://example.gov.cn/source',?,?)",
                documentId, slug, title, MARKER, category,
                Timestamp.valueOf(LocalDateTime.parse(publishedAt)), status, pinned, importance);
    }
}
