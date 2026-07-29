package cn.jianda.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class DocumentProcessingExecutorConfig {
    @Bean(name = "documentProcessingExecutor")
    public Executor documentProcessingExecutor(
            @Value("${jianda.processing.async-enabled:true}") boolean asyncEnabled) {
        if (!asyncEnabled) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("document-processing-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
