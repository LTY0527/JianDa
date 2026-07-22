package cn.jianda.collector;

import java.util.List;

public interface ContentCollector {
    String type();

    CollectedContent collect(CollectionRequest request);

    default List<CollectedContent> available() {
        return List.of();
    }
}
