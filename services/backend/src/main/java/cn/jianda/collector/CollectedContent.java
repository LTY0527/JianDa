package cn.jianda.collector;

import java.time.LocalDateTime;

public record CollectedContent(String fixtureId, String title, String sourceName, String sourceType,
                               String sourceUrl, String publisher, LocalDateTime publishedAt,
                               String body, String category) {
}
