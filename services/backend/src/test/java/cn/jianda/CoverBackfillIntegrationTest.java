package cn.jianda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import cn.jianda.ai.AiClient;
import cn.jianda.collector.CoverBackfillService;
import cn.jianda.security.AuthUser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jianda-cover-backfill-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jianda.upload-dir=./target/test-cover-backfill-uploads",
        "jianda.crawl.scheduler-enabled=false",
        "jianda.crawl.auto-ai-enabled=false"
})
class CoverBackfillIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CoverBackfillService service;
    @MockitoBean AiClient aiClient;

    private final AuthUser platform = new AuthUser(
            1, 1, "platform_admin", "平台管理员", "PLATFORM_ADMIN", "简达平台");
    private Path uploadRoot;

    @BeforeEach
    void prepare() throws Exception {
        uploadRoot = Path.of("./target/test-cover-backfill-uploads").toAbsolutePath().normalize();
        Files.createDirectories(uploadRoot.resolve("1"));
        when(aiClient.renderPdfFirstPage(any(Path.class), anyString()))
                .thenReturn(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10, 1, 2, 3});
    }

    @Test
    void pdfAndUploadedImageReceiveTraceableLocalCovers() throws Exception {
        Path pdf = uploadRoot.resolve("1/history.pdf");
        Path image = uploadRoot.resolve("1/history.png");
        Files.writeString(pdf, "test pdf bytes");
        Files.write(image, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10});
        long pdfId = insertDocument("历史 PDF", "PDF", "application/pdf", pdf, "history.pdf");
        long imageId = insertDocument("历史图片", "IMAGE", "image/png", image, "history.png");

        Map<String, Object> preview = service.preview(new CoverBackfillService.BackfillFilter(
                true, null, null, null, LocalDate.now().minusDays(1), LocalDate.now()));
        assertTrue(((Number) preview.get("total")).intValue() >= 2);

        Map<String, Object> started = service.startJob(
                new CoverBackfillService.BackfillFilter(
                        true, null, null, null, null, null),
                platform);
        long jobId = ((Number) started.get("jobId")).longValue();
        Map<String, Object> result = service.job(jobId);
        for (int attempt = 0;
                attempt < 100
                        && ("PENDING".equals(result.get("status"))
                        || "RUNNING".equals(result.get("status")));
                attempt++) {
            Thread.sleep(20);
            result = service.job(jobId);
        }
        assertEquals("SUCCEEDED", result.get("status"));
        assertEquals(result.get("total"), result.get("processed"));
        assertTrue(((Number) result.get("updated")).intValue() >= 2);
        assertEquals("PDF_FIRST_PAGE", jdbc.queryForObject(
                "SELECT cover_image_type FROM source_document WHERE id=?", String.class, pdfId));
        assertEquals("UPLOADED_ORIGINAL", jdbc.queryForObject(
                "SELECT cover_image_type FROM source_document WHERE id=?", String.class, imageId));
        assertTrue(jdbc.queryForObject(
                "SELECT image_reviewed FROM source_document WHERE id=?", Boolean.class, pdfId));
        assertTrue(jdbc.queryForObject(
                "SELECT image_cached FROM source_document WHERE id=?", Boolean.class, imageId));
    }

    private long insertDocument(
            String title, String sourceType, String mime, Path path, String originalName) {
        jdbc.update("INSERT INTO source_document(organization_id,title,file_name,file_type,storage_path,raw_text,"
                        + "page_count,processing_status,created_by,source_type,original_filename,mime_type,"
                        + "cover_image_type,image_reviewed,imported_at) "
                        + "VALUES (1,?,?,?,?, '',1,'UPLOADED',1,?,?,?,'CATEGORY_DEFAULT',FALSE,CURRENT_TIMESTAMP)",
                title, originalName, sourceType, path.toString(), sourceType, originalName, mime);
        return jdbc.queryForObject("SELECT MAX(id) FROM source_document", Long.class);
    }
}
