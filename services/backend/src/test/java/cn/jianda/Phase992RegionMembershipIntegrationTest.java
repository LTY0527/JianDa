package cn.jianda;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-phase992-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jianda.payment.provider=local_test",
        "jianda.payment.local-test-enabled=true"
})
@AutoConfigureMockMvc
class Phase992RegionMembershipIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    private String residentToken;
    private String staffAuth;

    @BeforeEach
    void prepare() throws Exception {
        jdbc.update("DELETE FROM demo_payment_session");
        jdbc.update("DELETE FROM membership_payment_session");
        jdbc.update("DELETE FROM resident_membership");
        jdbc.update("DELETE FROM source_registry WHERE domain='phase992-town-source.example'");
        jdbc.update("DELETE FROM published_item");
        jdbc.update("DELETE FROM processing_job WHERE document_id IN (SELECT id FROM source_document WHERE title LIKE 'Phase992%')");
        jdbc.update("DELETE FROM source_document WHERE title LIKE 'Phase992%'");
        residentToken = "phase992-resident-token";
        Long residentId = jdbc.queryForObject("SELECT id FROM resident_user ORDER BY id LIMIT 1", Long.class);
        jdbc.update("DELETE FROM resident_session WHERE resident_user_id=?", residentId);
        jdbc.update("INSERT INTO resident_session(resident_user_id,token_hash,expires_at) VALUES (?,?,?)",
                residentId, sha256(residentToken), Timestamp.valueOf(LocalDateTime.now().plusHours(1)));
        staffAuth = "Bearer " + login("platform_admin");
        insertPublished("phase992-dachang", "大场本地服务", "LOCAL_TOWN", "310113102", "宝山区", "上海市", "大场镇");
        insertPublished("phase992-gucun", "顾村本地服务", "LOCAL_TOWN", "310113109", "宝山区", "上海市", "顾村镇");
        insertPublished("phase992-baoshan", "宝山区共享服务", "DISTRICT_SHARED", "310113", "宝山区", "上海市", null);
        insertPublished("phase992-shanghai", "上海市共享服务", "CITY_SHARED", "310000", null, "上海市", null);
        insertPublished("phase992-national", "全国共享服务", "NATIONAL_SHARED", "100000", null, null, null);
        insertPublished("phase992-unclassified", "未分类内容", "UNCLASSIFIED", null, null, null, null);
        insertPublished("phase992-hongkou", "虹口局部内容", "CITY_SHARED", "310109", "虹口区", "上海市", null);
    }

    @Test
    void regionQueriesExcludeOtherTownUnclassifiedAndWrongDistrict() throws Exception {
        mvc.perform(get("/api/public/items").param("regionCode", "310113102"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].slug", containsInAnyOrder(
                        "phase992-dachang", "phase992-baoshan", "phase992-shanghai", "phase992-national")))
                .andExpect(jsonPath("$.data[*].slug", not(hasItem("phase992-gucun"))))
                .andExpect(jsonPath("$.data[*].slug", not(hasItem("phase992-unclassified"))))
                .andExpect(jsonPath("$.data[*].slug", not(hasItem("phase992-hongkou"))));
        mvc.perform(get("/api/public/search").param("keyword", "服务").param("regionCode", "310113109"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].slug", hasItem("phase992-gucun")))
                .andExpect(jsonPath("$.data[*].slug", not(hasItem("phase992-dachang"))));
    }

    @Test
    void supportedRegionsAndSourceRegistryExposeAuditableScope() throws Exception {
        mvc.perform(get("/api/public/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].region_code", containsInAnyOrder(
                        "310113102", "310113109", "310113112")));
        mvc.perform(get("/api/source-registries").header("Authorization", staffAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.region_code == '310113109')].street_or_town", hasItem("顾村镇")))
                .andExpect(jsonPath("$.data[?(@.region_code == '310113112')].street_or_town", hasItem("庙行镇")));
    }

    @Test
    void organizationCanAssignTownAndPublicationKeepsStrictRegionScope() throws Exception {
        String organizationAuth = "Bearer " + login("org_admin");
        String created = mvc.perform(post("/api/documents")
                        .header("Authorization", organizationAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Phase992顾村发布链路\",\"sourceName\":\"顾村镇测试来源\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long documentId = objectMapper.readTree(created).path("data").path("id").asLong();

        mvc.perform(put("/api/documents/{id}/region-scope", documentId)
                        .header("Authorization", organizationAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "localScope":"LOCAL_TOWN",
                                  "province":"上海市",
                                  "city":"上海市",
                                  "district":"宝山区",
                                  "streetOrTown":"顾村镇",
                                  "regionCode":"310113109"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.local_scope").value("LOCAL_TOWN"))
                .andExpect(jsonPath("$.data.region_code").value("310113109"))
                .andExpect(jsonPath("$.data.street_or_town").value("顾村镇"));

        mvc.perform(put("/api/documents/{id}/region-scope", documentId)
                        .header("Authorization", organizationAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "localScope":"LOCAL_TOWN",
                                  "province":"上海市",
                                  "city":"上海市",
                                  "district":"宝山区",
                                  "streetOrTown":"庙行镇",
                                  "regionCode":"310113109"
                                }
                                """))
                .andExpect(status().isBadRequest());

        long reviewerId = jdbc.queryForObject("SELECT id FROM staff_user WHERE username='org_admin'", Long.class);
        jdbc.update("INSERT INTO generated_content(document_id,content_type,title,content_json,plain_text) "
                + "VALUES (?,'SUMMARY','通俗摘要','[\"顾村测试摘要\"]','顾村测试摘要')", documentId);
        jdbc.update("INSERT INTO review_record(document_id,reviewer_id,action,comment) VALUES (?,?,'APPROVE','地区链路测试')",
                documentId, reviewerId);

        mvc.perform(post("/api/documents/{id}/publish", documentId)
                        .header("Authorization", organizationAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"顾村发布链路验收材料",
                                  "category":"社区服务",
                                  "sourceName":"顾村镇测试来源",
                                  "publishChannel":"COMMUNITY",
                                  "importanceLevel":"NORMAL"
                                }
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/public/items").param("regionCode", "310113109"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].slug", hasItem("guide-" + documentId)));
        mvc.perform(get("/api/public/items").param("regionCode", "310113102"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].slug", not(hasItem("guide-" + documentId))));
        mvc.perform(get("/api/public/items").param("regionCode", "310113112"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].slug", not(hasItem("guide-" + documentId))));
    }

    @Test
    void sourceRegistryCreationPersistsStrictTownScope() throws Exception {
        mvc.perform(post("/api/source-registries")
                        .header("Authorization", staffAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Phase992顾村官方来源",
                                  "domain":"phase992-town-source.example",
                                  "type":"GOVERNMENT",
                                  "authorityLevel":"A",
                                  "homepageUrl":"https://phase992-town-source.example/",
                                  "sectionUrl":"https://phase992-town-source.example/gucun/",
                                  "discoveryMode":"SECTION",
                                  "province":"上海市",
                                  "city":"上海市",
                                  "district":"宝山区",
                                  "streetOrTown":"顾村镇",
                                  "regionCode":"310113109"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.region_code").value("310113109"))
                .andExpect(jsonPath("$.data.street_or_town").value("顾村镇"));

        mvc.perform(post("/api/source-registries")
                        .header("Authorization", staffAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Phase992错误地域来源",
                                  "domain":"phase992-invalid-town.example",
                                  "type":"GOVERNMENT",
                                  "homepageUrl":"https://phase992-invalid-town.example/",
                                  "sectionUrl":"https://phase992-invalid-town.example/region/",
                                  "discoveryMode":"SECTION",
                                  "province":"上海市",
                                  "city":"上海市",
                                  "district":"宝山区",
                                  "streetOrTown":"庙行镇",
                                  "regionCode":"310113109"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void simulatedCommunityContentIsExplicitAndRegionIsolated() throws Exception {
        for (String code : java.util.List.of("310113102", "310113109", "310113112")) {
            mvc.perform(get("/api/public/community/posts").param("regionCode", code))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()", org.hamcrest.Matchers.greaterThanOrEqualTo(6)))
                    .andExpect(jsonPath("$.data[*].region_code", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(code))))
                    .andExpect(jsonPath("$.data[*].user_is_demo", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(true))));
        }
    }

    @Test
    void localProviderUsesPersistentPaymentSessionAndActivatesMembership() throws Exception {
        long planId = jdbc.queryForObject("SELECT id FROM membership_plan WHERE plan_code='WEEK_DEMO'", Long.class);
        String body = mvc.perform(post("/api/public/membership/payments")
                        .header("X-Resident-Token", residentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":" + planId + ",\"method\":\"ALIPAY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.qrPayload").value(org.hamcrest.Matchers.startsWith("jianda-local-payment://session/")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);
        String sessionId = json.path("data").path("sessionId").asText();
        mvc.perform(post("/api/internal/test/payments/{id}/confirm", sessionId)
                        .header("X-Resident-Token", residentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        mvc.perform(get("/api/public/membership/payments/{id}", sessionId)
                        .header("X-Resident-Token", residentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        org.junit.jupiter.api.Assertions.assertEquals(1L,
                jdbc.queryForObject("SELECT COUNT(*) FROM membership_payment_session WHERE status='SUCCESS'", Long.class));
        org.junit.jupiter.api.Assertions.assertEquals(1L,
                jdbc.queryForObject("SELECT COUNT(*) FROM resident_membership WHERE status='ACTIVE'", Long.class));
    }

    @Test
    void processingSnapshotIsLightweightAndTraceable() throws Exception {
        jdbc.update("INSERT INTO source_document(organization_id,title,file_type,raw_text,page_count,processing_status,created_by) "
                + "VALUES (1,'Phase992处理中材料','PDF','不应出现在轮询响应中的长正文',1,'PROCESSING',1)");
        long documentId = jdbc.queryForObject(
                "SELECT id FROM source_document WHERE title='Phase992处理中材料'", Long.class);
        jdbc.update("INSERT INTO processing_job(document_id,job_type,status,progress,stage,started_at,provider_id,model_id) "
                        + "VALUES (?,'FULL_PIPELINE','PROCESSING',45,'FACT_EXTRACT',CURRENT_TIMESTAMP,'mock','fixture-model')",
                documentId);

        mvc.perform(get("/api/documents/{id}/processing-snapshot", documentId)
                        .header("Authorization", staffAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(documentId))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.jobStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.data.stage").value("FACT_EXTRACT"))
                .andExpect(jsonPath("$.data.progress").value(45))
                .andExpect(jsonPath("$.data.raw_text").doesNotExist())
                .andExpect(jsonPath("$.data.fields").doesNotExist())
                .andExpect(jsonPath("$.data.generated").doesNotExist());
    }

    @Test
    void expandedOfficialSourcesAreSafeByDefault() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM source_registry WHERE domain IN "
                        + "('www.gov.cn','www.nhc.gov.cn','mzj.sh.gov.cn','wsjkw.sh.gov.cn','ybj.sh.gov.cn','www.shbsq.gov.cn')",
                Long.class);
        Long unsafe = jdbc.queryForObject("SELECT COUNT(*) FROM source_registry WHERE domain IN "
                        + "('www.gov.cn','www.nhc.gov.cn','mzj.sh.gov.cn','wsjkw.sh.gov.cn','ybj.sh.gov.cn','www.shbsq.gov.cn') "
                        + "AND (enabled=TRUE OR allow_auto_crawl=TRUE OR allow_auto_ai=TRUE OR requires_manual_review=FALSE)",
                Long.class);
        org.junit.jupiter.api.Assertions.assertEquals(6L, count);
        org.junit.jupiter.api.Assertions.assertEquals(0L, unsafe);
    }

    private void insertPublished(String slug, String title, String scope, String code,
            String district, String city, String town) {
        String storedTitle = "Phase992" + title;
        jdbc.update("INSERT INTO source_document(organization_id,title,file_type,raw_text,page_count,processing_status,created_by) "
                + "VALUES (1,?,'HTML',?,1,'PUBLISHED',1)", storedTitle, title);
        long documentId = jdbc.queryForObject("SELECT id FROM source_document WHERE title=?", Long.class, storedTitle);
        jdbc.update("INSERT INTO published_item(document_id,slug,title,summary,category,published_by,status,source_name,"
                        + "local_scope,region_code,district,city,street_or_town,province) "
                        + "VALUES (?,?,?,?, '社区服务',1,'PUBLISHED','测试权威来源',?,?,?,?,?,?)",
                documentId, slug, title, title, scope, code, district, city, town,
                "NATIONAL_SHARED".equals(scope) ? "全国" : "上海市");
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }


    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("username", username, "password", "Jianda@123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}
