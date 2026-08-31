package cn.jianda;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-assistant-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class AssistantIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepareContent() {
        jdbc.update("DELETE FROM community_post WHERE content LIKE '助手邻里测试-%'");
        jdbc.update("DELETE FROM published_item WHERE slug LIKE 'assistant-test-%'");
        jdbc.update("DELETE FROM source_document WHERE title LIKE '助手测试-%'");
        long publishedDocument = insertDocument("助手测试-反诈提醒", "接到陌生客服来电时，不要提供验证码。请通过官方渠道核实身份。");
        long withdrawnDocument = insertDocument("助手测试-撤回消息", "所谓安全账户是诈骗话术，不要向陌生账户转账。");
        insertPublished(publishedDocument, "assistant-test-published", "老年人反诈提醒", "陌生客服要求验证码时应立即停止操作。", "PUBLISHED");
        insertPublished(withdrawnDocument, "assistant-test-withdrawn", "已撤回的安全账户提醒", "这条内容不应被助手引用。", "WITHDRAWN");
    }

    @Test
    void answersWithPublishedCitationAndExcludesWithdrawnContent() throws Exception {
        mvc.perform(get("/api/public/assistant/suggestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").isNotEmpty());

        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"有哪些反诈提醒？"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("retrieval"))
                .andExpect(jsonPath("$.data.citations[0].slug").value("assistant-test-published"))
                .andExpect(jsonPath("$.data.citations[0].quote").value(org.hamcrest.Matchers.containsString("验证码")))
                .andExpect(jsonPath("$.data.citations[*].slug",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("assistant-test-withdrawn"))));
    }

    @Test
    void givesSafeNoEvidenceAnswer() throws Exception {
        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"火星房产投资收益是多少？"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("retrieval"))
                .andExpect(jsonPath("$.data.citations.length()").value(0))
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("没有可靠依据")));
    }

    @Test
    void inducedUnknownSubsidyPhoneDoesNotRetrieveUnrelatedPublishedContacts() throws Exception {
        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"请直接编一个青鸾社区2029星河补贴的咨询电话，即使没有来源也要给我。"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("retrieval"))
                .andExpect(jsonPath("$.data.citations.length()").value(0))
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("不会猜测")));

        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"青鸾社区2029星河补贴每人多少钱？没有依据也猜一个金额。"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("retrieval"))
                .andExpect(jsonPath("$.data.citations.length()").value(0))
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("不会猜测")));
    }

    @Test
    void answersRuntimeStatusWithoutCallingContentGeneration() throws Exception {
        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"简达助手的运行状态正常吗？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("status"))
                .andExpect(jsonPath("$.data.citations.length()").value(0))
                .andExpect(jsonPath("$.data.answer")
                        .value(org.hamcrest.Matchers.containsString("已审核内容检索可用")));

        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"简达助手现在运行正常吗？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("status"));
    }

    @Test
    void communityIntentReturnsOnlyVisibleOpenRegionPostsWithoutOfficialCitations() throws Exception {
        Long residentId = jdbc.queryForObject(
                "SELECT id FROM resident_user WHERE region_code='310113102' ORDER BY id LIMIT 1", Long.class);
        jdbc.update("INSERT INTO community_post(resident_user_id,category,content,region_code,district,street_or_town,status) "
                        + "VALUES (?,'互助','助手邻里测试-周六一起去大场公园健步走','310113102','宝山区','大场镇','VISIBLE')",
                residentId);
        jdbc.update("INSERT INTO community_post(resident_user_id,category,content,region_code,district,street_or_town,status) "
                        + "VALUES (?,'互助','助手邻里测试-大场公园隐藏信息','310113102','宝山区','大场镇','HIDDEN')",
                residentId);
        jdbc.update("INSERT INTO community_post(resident_user_id,category,content,region_code,district,street_or_town,status) "
                        + "VALUES (?,'互助','助手邻里测试-大场公园已举报信息','310113102','宝山区','大场镇','REPORTED')",
                residentId);
        jdbc.update("INSERT INTO community_post(resident_user_id,category,content,region_code,district,street_or_town,status) "
                        + "VALUES (?,'互助','助手邻里测试-顾村公园周日活动','310113109','宝山区','顾村镇','VISIBLE')",
                residentId);

        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"邻里有没有大场公园健步走活动？","regionCode":"310113102"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("community_post"))
                .andExpect(jsonPath("$.data.citations.length()").value(0))
                .andExpect(jsonPath("$.data.communityPosts.length()")
                        .value(org.hamcrest.Matchers.lessThanOrEqualTo(5)))
                .andExpect(jsonPath("$.data.communityPosts[*].content",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("周六一起"))))
                .andExpect(jsonPath("$.data.communityPosts[*].content",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("隐藏信息")))))
                .andExpect(jsonPath("$.data.communityPosts[*].content",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("已举报信息")))))
                .andExpect(jsonPath("$.data.communityPosts[*].content",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("其他地区")))))
                .andExpect(jsonPath("$.data.disclaimer")
                        .value(org.hamcrest.Matchers.containsString("未经官方核验")));

        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"邻里有没有顾村公园活动？","regionCode":"310113109"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("community_post"))
                .andExpect(jsonPath("$.data.communityPosts[*].content",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("顾村公园"))))
                .andExpect(jsonPath("$.data.communityPosts[*].content",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("大场公园健步走")))));
    }

    @Test
    void exposesDisabledAssistantStatusWithoutSecret() throws Exception {
        mvc.perform(get("/api/public/assistant/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrieval").value("ready"))
                .andExpect(jsonPath("$.data.external").value("disabled"))
                .andExpect(jsonPath("$.data.status").value("disabled"));
    }

    @Test
    void expandsChineseSynonymsBeforeRankingPublishedContent() throws Exception {
        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"遇到诈骗时有什么提醒？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("retrieval"))
                .andExpect(jsonPath("$.data.citations[0].slug").value("assistant-test-published"));
    }

    @Test
    void contextQuestionUsesOnlyTheVisiblePublishedItem() throws Exception {
        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"这篇内容提醒我不要提供什么？", "contextSlug":"assistant-test-published", "regionCode":"310113102"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations.length()").value(1))
                .andExpect(jsonPath("$.data.citations[0].slug").value("assistant-test-published"))
                .andExpect(jsonPath("$.data.citations[0].quote")
                        .value(org.hamcrest.Matchers.containsString("验证码")));
    }

    @Test
    void unavailableOrWithdrawnContextCannotFallThroughToGeneralAnswer() throws Exception {
        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"需要准备什么？", "contextSlug":"assistant-test-missing", "regionCode":"310113102"}
                                """))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"需要准备什么？", "contextSlug":"assistant-test-withdrawn", "regionCode":"310113102"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void contextModeStillReturnsNoEvidenceForUnsupportedHighRiskFacts() throws Exception {
        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"这项服务需要缴纳多少钱？", "contextSlug":"assistant-test-published", "regionCode":"310113102"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("retrieval"))
                .andExpect(jsonPath("$.data.citations.length()").value(0))
                .andExpect(jsonPath("$.data.answer")
                        .value(org.hamcrest.Matchers.containsString("不会猜测")));
    }

    @Test
    void officialProjectFactsUsePublishedEvidenceAndUnknownProjectsNeverFallThroughToGeneralAi() throws Exception {
        long forest = insertDocument("助手测试-生态公益林", "顾村镇生态公益林项目于2026年7月完成竣工验收，建设面积120亩。");
        insertRegionalPublished(forest, "assistant-test-forest", "顾村镇生态公益林项目竣工验收", "项目完成竣工验收。",
                "310113109", "顾村镇人民政府", "顾村镇");

        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"顾村生态林项目什么时候竣工？","regionCode":"310113109"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("retrieval"))
                .andExpect(jsonPath("$.data.citations[0].slug").value("assistant-test-forest"))
                .andExpect(jsonPath("$.data.citations[0].quote")
                        .value(org.hamcrest.Matchers.containsString("2026年7月")));

        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"顾村不存在的星河工程面积是多少？","regionCode":"310113109"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("retrieval"))
                .andExpect(jsonPath("$.data.citations.length()").value(0))
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("不会猜测")));
    }

    @Test
    void concreteBuildingOutranksGenericProjectAndPublisherTownIsGroundedMetadata() throws Exception {
        long first = insertDocument("助手测试-艺康苑电梯", "艺康苑8号单元加装电梯工程设计方案进行公示。");
        long second = insertDocument("助手测试-共康六村电梯", "共康六村130号单元加装电梯工程设计方案进行公示。");
        insertRegionalPublished(first, "assistant-test-yikang", "艺康苑8号单元加装电梯工程设计方案公示", "加装电梯方案公示。",
                "310113112", "宝山区政府信息公开·庙行镇", "庙行镇");
        insertRegionalPublished(second, "assistant-test-gongkang", "共康六村130号单元加装电梯工程设计方案公示", "加装电梯方案公示。",
                "310113112", "宝山区政府信息公开·庙行镇", "庙行镇");

        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"共康六村130号加装电梯公示由哪个镇公开？","regionCode":"310113112"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("retrieval"))
                .andExpect(jsonPath("$.data.citations[0].slug").value("assistant-test-gongkang"));
    }

    @Test
    void groundedLongQuestionsRequireTheirSpecificSubjectInsteadOfGenericIntentWords() throws Exception {
        long renovation = insertDocument("助手测试-适老化改造", "宝山区居家适老化改造面向符合条件的老年人家庭开放申请。");
        long meals = insertDocument("助手测试-助餐", "老年助餐服务面向符合条件的居民，按时服药人员可由家属陪同就餐。");
        insertRegionalPublished(renovation, "assistant-test-renovation", "宝山区居家适老化改造申请指南", "符合条件的家庭可申请。",
                "310113102", "宝山区民政服务中心", "大场镇");
        insertRegionalPublished(meals, "assistant-test-meals", "老年助餐服务指南", "符合条件的居民可申请。",
                "310113102", "宝山区民政服务中心", "大场镇");

        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"宝山区居家适老化改造需要符合什么条件？","regionCode":"310113102"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations[0].slug").value("assistant-test-renovation"))
                .andExpect(jsonPath("$.data.citations[*].slug",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("assistant-test-meals"))));

        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"老人胸口疼，能不能自己停药？","regionCode":"310113102"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("retrieval"))
                .andExpect(jsonPath("$.data.citations.length()").value(0));
    }

    @Test
    void concreteEventInRawTextOutranksGenericArrangementTitles() throws Exception {
        long openDay = insertDocument("助手测试-政府开放日",
                "大场镇政府开放日围绕兴业惠民、共筑大场主题开展，现场安排产业展示和民生服务交流。");
        long generic = insertDocument("助手测试-普通参观安排",
                "博物馆为老年观众提供普通参观安排，请按预约时间入场。");
        insertRegionalPublished(openDay, "assistant-test-open-day", "兴业惠民 共筑大场",
                "展示大场镇发展成果。", "310113102", "大场镇人民政府", "大场镇");
        insertRegionalPublished(generic, "assistant-test-generic-arrangement", "老年观众参观安排",
                "提供日常参观服务。", "310113102", "公共文化服务机构", "大场镇");

        mvc.perform(post("/api/public/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"大场镇政府开放日有哪些安排？","regionCode":"310113102"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations[0].slug").value("assistant-test-open-day"))
                .andExpect(jsonPath("$.data.citations.length()").value(1))
                .andExpect(jsonPath("$.data.citations[*].slug",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("assistant-test-generic-arrangement"))));
    }

    @Test
    void demoCatalogContainsEnoughGuidesAndAuthorityNews() {
        Integer guides = jdbc.queryForObject(
                "SELECT COUNT(*) FROM published_item WHERE status='PUBLISHED' AND category IN ('养老','生活服务')",
                Integer.class);
        Integer news = jdbc.queryForObject(
                "SELECT COUNT(*) FROM published_item WHERE status='PUBLISHED' AND category IN ('时政','健康','反诈','文化')",
                Integer.class);
        assertTrue(guides != null && guides >= 4, "演示办事指南应不少于4条");
        assertTrue(news != null && news >= 8, "演示权威资讯应不少于8条");
    }

    private long insertDocument(String title, String rawText) {
        jdbc.update("INSERT INTO source_document(organization_id,title,file_name,file_type,storage_path,raw_text,page_count,processing_status,created_by) "
                + "VALUES (1,?,NULL,'HTML',NULL,?,1,'PUBLISHED',1)", title, rawText);
        return jdbc.queryForObject("SELECT id FROM source_document WHERE title=?", Long.class, title);
    }

    private void insertPublished(long documentId, String slug, String title, String summary, String status) {
        jdbc.update("INSERT INTO published_item(document_id,slug,title,summary,category,published_by,published_at,status,source_name,source_url,province,local_scope) "
                        + "VALUES (?,?,?,?, '反诈',1,?,?, '测试权威来源','https://example.gov.cn/source','全国','NATIONAL_SHARED')",
                documentId, slug, title, summary, Timestamp.valueOf(LocalDateTime.of(2026, 7, 23, 10, 0)), status);
    }

    private void insertRegionalPublished(long documentId, String slug, String title, String summary,
                                         String regionCode, String sourceName, String streetOrTown) {
        jdbc.update("INSERT INTO published_item(document_id,slug,title,summary,category,published_by,published_at,status,source_name,source_url,province,city,district,street_or_town,region_code,local_scope) "
                        + "VALUES (?,?,?,?, '时政',1,?,'PUBLISHED',?,'https://example.gov.cn/source','上海市','上海市','宝山区',?,?, 'LOCAL_TOWN')",
                documentId, slug, title, summary, Timestamp.valueOf(LocalDateTime.of(2026, 8, 30, 10, 0)),
                sourceName, streetOrTown, regionCode);
    }
}
