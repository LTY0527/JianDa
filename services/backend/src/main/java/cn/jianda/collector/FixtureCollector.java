package cn.jianda.collector;

import cn.jianda.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FixtureCollector implements ContentCollector {
    private final ObjectMapper objectMapper;
    private final Path fixturePath;
    private List<CollectedContent> fixtures = List.of();

    public FixtureCollector(ObjectMapper objectMapper,
                            @Value("${jianda.public-fixture:../../fixtures/public-information.json}") String fixturePath) {
        this.objectMapper = objectMapper;
        this.fixturePath = Paths.get(fixturePath).toAbsolutePath().normalize();
    }

    @PostConstruct
    void load() throws IOException {
        if (!Files.isRegularFile(fixturePath)) {
            throw new IllegalStateException("公开信息 fixture 不存在：" + fixturePath);
        }
        List<Map<String, Object>> rows = objectMapper.readValue(Files.readString(fixturePath), new TypeReference<>() {});
        List<CollectedContent> loaded = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            loaded.add(new CollectedContent(text(row, "id"), text(row, "title"), text(row, "sourceName"),
                    text(row, "sourceType"), text(row, "sourceUrl"), text(row, "publisher"),
                    LocalDate.parse(text(row, "publishedAt")).atStartOfDay(), text(row, "body"),
                    text(row, "category")));
        }
        fixtures = List.copyOf(loaded);
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
