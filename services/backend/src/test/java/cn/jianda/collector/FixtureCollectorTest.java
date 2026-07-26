package cn.jianda.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class FixtureCollectorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadsAndParsesDefaultClasspathFixture() throws Exception {
        FixtureCollector collector = new FixtureCollector(objectMapper, "");

        collector.load();

        assertThat(new ClassPathResource(FixtureCollector.DEFAULT_FIXTURE_RESOURCE).exists()).isTrue();
        assertThat(collector.available()).hasSize(3);
        assertThat(collector.available())
                .extracting(CollectedContent::fixtureId)
                .containsExactly(
                        "anti-fraud-elderly-2026",
                        "hypertension-daily-care-2026",
                        "community-elderly-service-2026");
        assertThat(collector.available())
                .allSatisfy(item -> {
                    assertThat(item.title()).isNotBlank();
                    assertThat(item.body()).isNotBlank();
                    assertThat(item.publishedAt()).isNotNull();
                });
    }

    @Test
    void loadsExplicitExternalFixture(@TempDir Path tempDir) throws Exception {
        Path fixture = tempDir.resolve("custom-fixture.json");
        Files.writeString(fixture, """
                [{
                  "id": "custom-fixture",
                  "title": "外部示例",
                  "sourceName": "测试机构",
                  "sourceType": "GOVERNMENT",
                  "sourceUrl": "https://example.test/fixture",
                  "publisher": "测试机构",
                  "publishedAt": "2026-07-25",
                  "category": "政策",
                  "body": "用于验证外部 fixture 配置。"
                }]
                """);
        FixtureCollector collector = new FixtureCollector(objectMapper, fixture.toString());

        collector.load();

        assertThat(collector.available()).singleElement()
                .extracting(CollectedContent::fixtureId)
                .isEqualTo("custom-fixture");
    }

    @Test
    void reportsConfiguredExternalFixturePathWhenMissing(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("missing.json");
        FixtureCollector collector = new FixtureCollector(objectMapper, missing.toString());

        assertThatThrownBy(collector::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("配置的外部公开信息 fixture 不存在")
                .hasMessageContaining(missing.toAbsolutePath().normalize().toString());
    }

    @Test
    void reportsInvalidFixtureJson(@TempDir Path tempDir) throws Exception {
        Path invalid = tempDir.resolve("invalid.json");
        Files.writeString(invalid, "{not-json}");
        FixtureCollector collector = new FixtureCollector(objectMapper, invalid.toString());

        assertThatThrownBy(collector::load)
                .isInstanceOf(JsonProcessingException.class);
    }
}
