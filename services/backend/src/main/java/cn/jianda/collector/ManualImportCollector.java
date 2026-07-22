package cn.jianda.collector;

import cn.jianda.common.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class ManualImportCollector implements ContentCollector {
    @Override
    public String type() {
        return "MANUAL";
    }

    @Override
    public CollectedContent collect(CollectionRequest request) {
        if (request.title() == null || request.title().isBlank() || request.body() == null || request.body().isBlank()) {
            throw new BusinessException(400, "标题和正文不能为空");
        }
        if (request.sourceUrl() == null || request.sourceUrl().isBlank()) {
            throw new BusinessException(400, "来源 URL 不能为空");
        }
        return new CollectedContent(null, request.title().trim(), request.sourceName(), request.sourceType(),
                request.sourceUrl().trim(), request.publisher(), request.publishedAt(), request.body().trim(),
                request.category());
    }
}
