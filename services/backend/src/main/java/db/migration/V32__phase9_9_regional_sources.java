package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V32__phase9_9_regional_sources extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        dropLegacyDomainUnique(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE UNIQUE INDEX uk_source_registry_domain_region "
                    + "ON source_registry(domain, region_code)");
        }
        insertRegion(connection, "宝山区政府信息公开·顾村镇",
                "https://xxgk.shbsq.gov.cn/infoDirectory.html?type=dept&dept=003005",
                "顾村镇", "310113109");
        insertRegion(connection, "宝山区政府信息公开·庙行镇",
                "https://xxgk.shbsq.gov.cn/infoDirectory.html?dept=003007&rn=%E5%BA%99%E8%A1%8C%E9%95%87&type=dept",
                "庙行镇", "310113112");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE source_registry SET enabled=TRUE,crawl_mode='SCHEDULED',"
                    + "allow_auto_crawl=TRUE,schedule_mode='INTERVAL',interval_hours=12,"
                    + "next_run_at=COALESCE(next_run_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP "
                    + "WHERE region_code='310113102'");
        }
    }

    private static void dropLegacyDomainUnique(Connection connection) throws Exception {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
        if (product.contains("h2")) {
            String constraint = null;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT k.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k "
                            + "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS c ON c.CONSTRAINT_NAME=k.CONSTRAINT_NAME "
                            + "WHERE UPPER(k.TABLE_NAME)='SOURCE_REGISTRY' AND UPPER(k.COLUMN_NAME)='DOMAIN' "
                            + "AND c.CONSTRAINT_TYPE='UNIQUE'")) {
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) constraint = rows.getString(1);
                }
            }
            if (constraint != null) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE source_registry DROP CONSTRAINT \""
                            + constraint.replace("\"", "\"\"") + "\"");
                }
            }
            return;
        }
        String index = null;
        try (ResultSet rows = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), null, "source_registry", true, false)) {
            while (rows.next()) {
                if ("domain".equalsIgnoreCase(rows.getString("COLUMN_NAME"))) {
                    index = rows.getString("INDEX_NAME");
                    break;
                }
            }
        }
        if (index != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE source_registry DROP INDEX `"
                        + index.replace("`", "``") + "`");
            }
        }
    }

    private static void insertRegion(Connection connection, String name, String sectionUrl,
                                     String town, String regionCode) throws Exception {
        String sql = "INSERT INTO source_registry(domain,allowed_hosts,source_name,source_type,authority_level,"
                + "enabled,crawl_mode,discovery_mode,homepage_url,section_url,max_articles_per_run,"
                + "allow_auto_crawl,allow_auto_ai,allow_image_candidates,allow_image_cache,image_cache_allowed,"
                + "requires_manual_review,schedule_mode,interval_hours,next_run_at,province,city,district,"
                + "street_or_town,region_code,include_keywords,exclude_keywords) "
                + "SELECT 'xxgk.shbsq.gov.cn','xxgk.shbsq.gov.cn',?,'GOVERNMENT','A',TRUE,'SCHEDULED','SECTION',"
                + "'https://xxgk.shbsq.gov.cn/',?,12,TRUE,FALSE,TRUE,TRUE,TRUE,TRUE,'INTERVAL',12,"
                + "CURRENT_TIMESTAMP,'上海市','上海市','宝山区',?,?,"
                + "'养老,助餐,健康,活动,便民,社区,安全,反诈,电梯','处罚,预算,决算,人事任免,采购意向' "
                + "WHERE NOT EXISTS (SELECT 1 FROM source_registry WHERE domain='xxgk.shbsq.gov.cn' AND region_code=?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setString(2, sectionUrl);
            statement.setString(3, town);
            statement.setString(4, regionCode);
            statement.setString(5, regionCode);
            statement.executeUpdate();
        }
    }
}
