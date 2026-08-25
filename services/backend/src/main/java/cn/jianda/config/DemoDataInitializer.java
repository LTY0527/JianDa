package cn.jianda.config;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoDataInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final boolean demoContentEnabled;

    public DemoDataInitializer(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
            @Value("${jianda.demo-content-enabled:true}") boolean demoContentEnabled) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.demoContentEnabled = demoContentEnabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization", Integer.class) > 0) {
            if (demoContentEnabled) {
                seedPublicSources();
                seedPublishedCatalog();
                seedResidentCommunity();
            }
            return;
        }
        jdbc.update("INSERT INTO organization(name,code,type) VALUES (?,?,?)", "简达平台运营中心", "PLATFORM", "PLATFORM");
        jdbc.update("INSERT INTO organization(name,code,type) VALUES (?,?,?)", "浦江街道社区服务中心", "PUJIANG", "COMMUNITY");
        jdbc.update("INSERT INTO organization(name,code,type) VALUES (?,?,?)", "城市人民医院", "CITY_HOSPITAL", "HOSPITAL");
        String password = passwordEncoder.encode("Jianda@123");
        jdbc.update("INSERT INTO staff_user(organization_id,username,password_hash,display_name,role) VALUES (1,?,?,?,?)",
                "platform_admin", password, "平台管理员", "PLATFORM_ADMIN");
        jdbc.update("INSERT INTO staff_user(organization_id,username,password_hash,display_name,role) VALUES (2,?,?,?,?)",
                "org_admin", password, "李敏", "ORG_ADMIN");
        jdbc.update("INSERT INTO staff_user(organization_id,username,password_hash,display_name,role) VALUES (2,?,?,?,?)",
                "reviewer", password, "王芳", "REVIEWER");
        if (demoContentEnabled) {
            seedPublishedGuide();
            seedPublicSources();
            seedPublishedCatalog();
            seedResidentCommunity();
        }
    }

    private void seedResidentCommunity() {
        String password = passwordEncoder.encode("Resident@123");
        for (String[] resident : List.of(
                new String[]{"demo_chen", "陈阿姨"}, new String[]{"demo_li", "李叔叔"},
                new String[]{"demo_wang", "王老师"}, new String[]{"demo_zhou", "周师傅"},
                new String[]{"demo_zhang", "张奶奶"})) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM resident_user WHERE username=?", Integer.class, resident[0]);
            if (exists != null && exists == 0) {
                jdbc.update("INSERT INTO resident_user(username,password_hash,nickname,district,street_or_town,region_code,is_demo) "
                                + "VALUES (?,?,?,?,?,?,TRUE)", resident[0], password, resident[1], "宝山区", "大场镇", "310113102");
            }
        }
        Integer posts = jdbc.queryForObject("SELECT COUNT(*) FROM community_post WHERE is_demo=TRUE", Integer.class);
        if (posts != null && posts == 0) {
            seedDemoPost("demo_chen", "互助", "想请教大家，社区智能手机课堂在哪里查看报名通知？");
            seedDemoPost("demo_li", "活动", "今天在简达看到了大场镇的活动通知，提醒大家先核对官方时间再出门。");
            seedDemoPost("demo_wang", "最新", "邻里交流请不要发布身份证、银行卡和具体门牌等个人信息。");
        }
    }

    private void seedDemoPost(String username, String category, String content) {
        Long userId = jdbc.queryForObject("SELECT id FROM resident_user WHERE username=?", Long.class, username);
        jdbc.update("INSERT INTO community_post(resident_user_id,category,content,region_code,district,street_or_town,is_demo) "
                        + "VALUES (?,?,?,'310113102','宝山区','大场镇',TRUE)", userId, category, content);
    }

    private void seedPublicSources() {
        seedSource("国家反诈中心", "GOVERNMENT", "https://www.mps.gov.cn", "国家反诈中心", "用于离线反诈提醒 fixture");
        seedSource("城市人民医院", "HOSPITAL", "https://www.city-hospital.example", "城市人民医院", "用于离线健康科普 fixture");
        seedSource("浦江街道办事处", "PUBLIC_INSTITUTION", "https://www.pujiang.gov.cn", "浦江街道办事处", "用于离线社区养老通知 fixture");
    }

    private void seedSource(String name, String type, String url, String publisher, String notes) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM content_source WHERE source_name=?", Integer.class, name);
        if (count != null && count == 0) {
            jdbc.update("INSERT INTO content_source(organization_id,source_type,source_name,source_url,publisher,status,whitelist_status,enabled,notes) "
                    + "VALUES (NULL,?,?,?,?,'ACTIVE','APPROVED',TRUE,?)", type, name, url, publisher, notes);
        }
    }
    private void seedPublishedGuide() {
        String raw = "浦江街道老年补贴办理通知\n补贴对象为具有本市户籍且年满八十周岁的老年人。已享受同类补贴待遇的，不重复发放。\n"
                + "申请材料：身份证及户口簿原件、本人银行卡复印件、近期一寸免冠照片一张。\n"
                + "请申请人至户籍所在地社区服务窗口提出申请。咨询电话：021-12345。";
        jdbc.update("INSERT INTO source_document(organization_id,title,file_name,file_type,raw_text,page_count,processing_status,created_by) VALUES (2,?,?,?,?,3,'PUBLISHED',2)",
                "老年补贴申请指南", "浦江街道老年补贴办理通知.pdf", "application/pdf", raw);
        Long documentId = jdbc.queryForObject("SELECT MAX(id) FROM source_document", Long.class);
        jdbc.update("INSERT INTO generated_content(document_id,content_type,title,content_json,plain_text,status) VALUES (?,?,?,?,?,'PUBLISHED')",
                documentId, "SUMMARY", "三句话看懂",
                "[\"本市户籍、年满80周岁的老人可以申请生活补贴。\",\"带齐身份证、户口簿、银行卡和照片到社区办理。\",\"审核通过后补贴发到本人银行卡。\"]",
                "年满80周岁的本市户籍老人，可带齐材料到社区申请生活补贴。");
        jdbc.update("INSERT INTO generated_content(document_id,content_type,title,content_json,plain_text,status) VALUES (?,?,?,?,?,'PUBLISHED')",
                documentId, "STEP_CARDS", "办理步骤",
                "[{\"order\":1,\"title\":\"准备材料\",\"description\":\"准备身份证、户口簿、银行卡和照片。\"},{\"order\":2,\"title\":\"到社区申请\",\"description\":\"前往户籍所在地社区服务窗口。\"},{\"order\":3,\"title\":\"填写并提交\",\"description\":\"填写申请表并提交材料。\"},{\"order\":4,\"title\":\"等待审核\",\"description\":\"一般10个工作日内完成。\"},{\"order\":5,\"title\":\"查询结果\",\"description\":\"通过后补贴发到本人银行卡。\"}]",
                "准备材料后到社区申请，填写提交并等待审核结果。");
        jdbc.update("INSERT INTO review_record(document_id,reviewer_id,action,comment) VALUES (?,3,'APPROVE','演示数据已人工复核')", documentId);
        jdbc.update("INSERT INTO published_item(document_id,slug,title,summary,category,published_by,source_name,source_url) VALUES (?,?,?,?,?,?,?,?)",
                documentId, "elderly-subsidy", "老年补贴申请指南", "年满 80 周岁的本市户籍老人，可到社区申请生活补贴。", "养老", 3,
                "浦江街道社区服务中心", "https://example.org/pujiang/elderly-subsidy");
    }

    private void seedPublishedCatalog() {
        List<DemoContent> contents = List.of(
                new DemoContent("social-security-card-renewal", "社会保障卡到期换领指南",
                        "社会保障卡有效期届满前，可携带本人身份证和原卡到就近服务网点申请换领。",
                        "生活服务", "市民服务中心", "https://www.pujiang.gov.cn/service/social-security-card",
                        LocalDate.of(2026, 7, 18),
                        "社会保障卡到期前六十日内可以申请换领。申请人需携带本人身份证原件和原社会保障卡，到就近社区事务受理中心办理；行动不便人员可拨打021-12345咨询代办。窗口受理后七个工作日内制卡，领卡时间以短信通知为准。",
                        "本人身份证原件、原社会保障卡", "就近社区事务受理中心", "到期前60日内申请，受理后7个工作日内制卡",
                        "[{\"order\":1,\"title\":\"核对有效期\",\"description\":\"查看社会保障卡正面的有效期限。\"},{\"order\":2,\"title\":\"准备材料\",\"description\":\"携带身份证原件和原社会保障卡。\"},{\"order\":3,\"title\":\"窗口申请\",\"description\":\"到就近社区事务受理中心提交换领申请。\"},{\"order\":4,\"title\":\"领取新卡\",\"description\":\"收到短信后按通知时间领取并激活。\"}]"),
                new DemoContent("senior-canteen-application", "长者食堂助餐服务申请指南",
                        "年满60周岁的本区居民可登记长者助餐服务，符合条件人员可同步申请补贴。",
                        "养老", "浦江街道办事处", "https://www.pujiang.gov.cn/community/senior-canteen",
                        LocalDate.of(2026, 7, 16),
                        "本区年满六十周岁的居民可申请长者食堂助餐服务。请携带身份证和户口簿到居住地社区登记，符合助餐补贴条件的还需提供相关困难证明。社区工作日受理，审核一般需要五个工作日，审核结果通过电话告知。",
                        "身份证、户口簿；申请补贴人员另带困难证明", "居住地社区服务窗口", "工作日受理，5个工作日内完成审核",
                        "[{\"order\":1,\"title\":\"确认条件\",\"description\":\"确认年龄、居住地和助餐补贴适用条件。\"},{\"order\":2,\"title\":\"社区登记\",\"description\":\"携带材料到居住地社区服务窗口登记。\"},{\"order\":3,\"title\":\"等待审核\",\"description\":\"社区在五个工作日内核对材料。\"},{\"order\":4,\"title\":\"选择餐点\",\"description\":\"审核通过后选择就近长者食堂或送餐服务。\"}]"),
                new DemoContent("home-accessibility-renovation", "居家适老化改造申请指南",
                        "符合条件的老年人家庭可申请扶手、防滑和照明等基础适老化改造评估。",
                        "养老", "区民政服务中心", "https://www.pujiang.gov.cn/civil-affairs/home-renovation",
                        LocalDate.of(2026, 7, 12),
                        "年满六十周岁且有居家适老化需求的本区老年人家庭，可向居住地社区提出评估申请。申请时提交身份证、户口簿、房屋居住证明和申请表。社区登记后安排专业人员上门评估，改造范围和费用以书面方案为准，申请阶段不收取评估费。",
                        "身份证、户口簿、房屋居住证明、申请表", "居住地社区服务窗口", "登记后10个工作日内安排上门评估",
                        "[{\"order\":1,\"title\":\"提交申请\",\"description\":\"向居住地社区提交身份和房屋材料。\"},{\"order\":2,\"title\":\"上门评估\",\"description\":\"专业人员了解通行、洗浴和照明需求。\"},{\"order\":3,\"title\":\"确认方案\",\"description\":\"核对改造项目、费用和施工安排。\"},{\"order\":4,\"title\":\"施工验收\",\"description\":\"施工完成后由申请人和服务人员共同验收。\"}]"),
                new DemoContent("summer-heat-health", "高温天气老年人健康防护提醒",
                        "高温时段减少外出，规律服药并及时补水，出现胸闷或意识异常应尽快就医。",
                        "健康", "城市人民医院", "https://www.city-hospital.example/health/summer-heat",
                        LocalDate.of(2026, 7, 22),
                        "连续高温期间，老年人应避免在十一时至十六时长时间户外活动，少量多次补水，并按医嘱规律服药。出现持续胸闷、剧烈头痛、意识异常或体温明显升高时，应立即转移到阴凉处并及时就医。",
                        null, null, null, null),
                new DemoContent("anti-fraud-screen-sharing", "警惕远程协助和屏幕共享诈骗",
                        "陌生客服要求安装远程协助软件或开启屏幕共享时，应立即停止操作并通过官方渠道核实。",
                        "反诈", "国家反诈中心", "https://www.mps.gov.cn/anti-fraud/screen-sharing",
                        LocalDate.of(2026, 7, 21),
                        "诈骗分子常冒充平台客服，以退款、理赔为由诱导开启屏幕共享。屏幕共享可能暴露短信验证码和支付密码。正规客服不会要求转账到安全账户；遇到此类来电应挂断并通过官方应用核实。",
                        null, null, null, null),
                new DemoContent("rainstorm-travel-safety", "强降雨期间公共出行安全提示",
                        "强降雨时避开积水路段和地下空间，关注公共交通调整及属地部门通知。",
                        "时政", "市应急管理局", "https://www.pujiang.gov.cn/emergency/rainstorm",
                        LocalDate.of(2026, 7, 20),
                        "强降雨期间请减少非必要外出，不进入河道、下穿通道和积水地下空间。确需出行时关注气象预警和公共交通调整，不要冒险涉水通行；遇到险情及时拨打应急电话。",
                        null, null, null, null),
                new DemoContent("vaccination-summer-notice", "老年人夏季疫苗接种健康提示",
                        "接种前如实告知健康情况，接种后留观并关注持续发热等异常反应。",
                        "健康", "区疾病预防控制中心", "https://www.city-hospital.example/public-health/vaccination",
                        LocalDate.of(2026, 7, 19),
                        "老年人接种疫苗前应携带有效证件，如实告知慢性病、用药和过敏情况。接种后按要求留观三十分钟，当天避免剧烈运动；如出现持续高热或严重不适，应及时就医并联系接种门诊。",
                        null, null, null, null),
                new DemoContent("pension-qualification-reminder", "养老待遇资格认证便民提醒",
                        "资格认证可通过官方线上渠道或社区协助办理，不收取认证费用。",
                        "时政", "市人力资源和社会保障局", "https://www.pujiang.gov.cn/hrss/pension-certification",
                        LocalDate.of(2026, 7, 17),
                        "领取养老待遇人员可通过官方应用完成资格认证，也可携带身份证到社区服务窗口寻求协助。认证不收取费用，工作人员不会索要银行卡密码或短信验证码，具体周期以人社部门通知为准。",
                        null, null, null, null),
                new DemoContent("museum-senior-service", "市民文化馆老年友好服务安排",
                        "文化馆提供大字活动单、无障碍通道和志愿者引导，部分活动需提前预约。",
                        "文化", "市民文化馆", "https://www.pujiang.gov.cn/culture/senior-service",
                        LocalDate.of(2026, 7, 15),
                        "市民文化馆工作日和周末正常开放，为老年访客提供大字活动单、无障碍通道和志愿者引导。公益讲座和手工课程名额有限，请通过文化馆官方电话或服务台预约，以现场安排为准。",
                        null, null, null, null),
                new DemoContent("fall-prevention-home", "老年人居家防跌倒健康建议",
                        "保持通道明亮整洁，浴室铺设防滑垫，夜间起身应先坐稳再缓慢行走。",
                        "健康", "城市人民医院", "https://www.city-hospital.example/health/fall-prevention",
                        LocalDate.of(2026, 7, 14),
                        "老年人居家应清理通道杂物，保持夜间照明，浴室使用防滑垫和扶手。服用可能引起头晕的药物后不要突然起身；发生跌倒且出现明显疼痛、活动受限或意识变化时，不要勉强移动并及时求助。",
                        null, null, null, null),
                new DemoContent("government-subsidy-scam", "防范冒充政府补贴发放诈骗",
                        "补贴发放不会要求先缴手续费，也不会通过陌生链接收集银行卡密码和验证码。",
                        "反诈", "国家反诈中心", "https://www.mps.gov.cn/anti-fraud/subsidy",
                        LocalDate.of(2026, 7, 13),
                        "近期有诈骗分子冒充政府工作人员，以发放养老补贴、惠民补助为由发送陌生链接。正规补贴申请不会要求先缴保证金或手续费，也不会索要银行卡密码和验证码。请通过政府官网或社区窗口核实。",
                        null, null, null, null));

        for (DemoContent content : contents) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM published_item WHERE slug=?", Integer.class, content.slug());
            if (count != null && count > 0) continue;
            long organizationId = content.category().equals("健康") ? 3 : 2;
            jdbc.update("INSERT INTO source_document(organization_id,title,file_name,file_type,raw_text,page_count,processing_status,created_by) "
                            + "VALUES (?,?,NULL,'HTML',?,1,'PUBLISHED',1)",
                    organizationId, content.title(), content.rawText());
            Long documentId = jdbc.queryForObject("SELECT MAX(id) FROM source_document", Long.class);
            jdbc.update("INSERT INTO published_item(document_id,slug,title,summary,category,published_by,published_at,source_name,source_url) "
                            + "VALUES (?,?,?,?,?,?,?, ?,?)",
                    documentId, content.slug(), content.title(), content.summary(), content.category(), 1,
                    Timestamp.valueOf(content.publishedDate().atTime(9, 0)), content.sourceName(), content.sourceUrl());
            if (content.stepsJson() != null) {
                jdbc.update("INSERT INTO generated_content(document_id,content_type,title,content_json,plain_text,status) "
                                + "VALUES (?,'SUMMARY','三句话看懂',?,?,'PUBLISHED')",
                        documentId, "[\"" + content.summary() + "\"]", content.summary());
                jdbc.update("INSERT INTO generated_content(document_id,content_type,title,content_json,plain_text,status) "
                                + "VALUES (?,'STEP_CARDS','办理步骤',?,?,'PUBLISHED')",
                        documentId, content.stepsJson(), "请按页面列出的步骤办理，并以窗口最终要求为准。");
                seedGuideField(documentId, "MATERIAL", "所需材料", content.materials(), content.rawText());
                seedGuideField(documentId, "LOCATION", "办理地点", content.location(), content.rawText());
                seedGuideField(documentId, "DEADLINE", "办理时限", content.deadline(), content.rawText());
            }
        }
    }

    private void seedGuideField(long documentId, String type, String label, String value, String quote) {
        jdbc.update("INSERT INTO extracted_field(document_id,field_type,field_label,field_value,page_no,source_quote,confidence,review_status) "
                        + "VALUES (?,?,?,?,1,?,0.9900,'CONFIRMED')",
                documentId, type, label, value, quote);
    }

    private record DemoContent(
            String slug,
            String title,
            String summary,
            String category,
            String sourceName,
            String sourceUrl,
            LocalDate publishedDate,
            String rawText,
            String materials,
            String location,
            String deadline,
            String stepsJson) {}
}
