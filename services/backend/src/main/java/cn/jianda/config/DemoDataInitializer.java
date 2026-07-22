package cn.jianda.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoDataInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public DemoDataInitializer(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organization", Integer.class) > 0) {
            seedPublicSources();
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
        seedPublishedGuide();
        seedPublicSources();
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
}

