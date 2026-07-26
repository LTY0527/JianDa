package cn.jianda.collector;

import cn.jianda.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class FixtureCollector implements ContentCollector {
    static final String DEFAULT_FIXTURE_RESOURCE = "fixtures/public-information.json";

    private final ObjectMapper objectMapper;
    private final String externalFixturePath;
    private List<CollectedContent> fixtures = List.of();

    public FixtureCollector(ObjectMapper objectMapper,
                            @Value("${jianda.public-fixture:}") String fixturePath) {
        this.objectMapper = objectMapper;
        this.externalFixturePath = fixturePath == null ? "" : fixturePath.trim();
    }

    @PostConstruct
    void load() throws IOException {
        try (InputStream input = openFixture()) {
            List<Map<String, Object>> rows = objectMapper.readValue(input, new TypeReference<>() {});
            List<CollectedContent> loaded = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                loaded.add(new CollectedContent(text(row, "id"), text(row, "title"), text(row, "sourceName"),
                        text(row, "sourceType"), text(row, "sourceUrl"), text(row, "publisher"),
                        LocalDate.parse(text(row, "publishedAt")).atStartOfDay(), text(row, "body"),
                        text(row, "category")));
            }
            fixtures = List.copyOf(loaded);
        }
    }

    private InputStream openFixture() throws IOException {
        if (!externalFixturePath.isBlank()) {
            Path path = Paths.get(externalFixturePath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("配置的外部公开信息 fixture 不存在：" + path);
            }
            return Files.newInputStream(path);
        }

        ClassPathResource resource = new ClassPathResource(DEFAULT_FIXTURE_RESOURCE);
        if (!resource.exists()) {
            throw new IllegalStateException("classpath 公开信息 fixture 不存在：" + DEFAULT_FIXTURE_RESOURCE);
        }
        return resource.getInputStream();
    }

    @Override
    public String type() {
        return "FIXTURE";
    }

    @Override
    public CollectedContent collect(CollectionRequest request) {
        return fixtures.stream().filter(item -> item.fixtureId().equals(request.fixtureId())).findFirst()
                .orElseThrow(() -> new BusinessException(404, "示例公开信息不存在"));
    }

    @Override
    public List<CollectedContent> available() {
        return fixtures;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("fixture 缺少字段：" + key);
        }
        return value.toString();
    }
}
