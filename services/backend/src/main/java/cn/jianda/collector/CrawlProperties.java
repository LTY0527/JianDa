package cn.jianda.collector;

import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jianda.crawl")
public record CrawlProperties(
        boolean schedulerEnabled,
        LocalTime dailyTime,
        String timezone,
        int maxArticlesPerSource,
        int globalMaxArticlesPerRun,
        boolean autoAiEnabled,
        int maxFailureRetries,
        int dailyAiMaxArticles,
        int dailyAiMaxTokens) {
}
