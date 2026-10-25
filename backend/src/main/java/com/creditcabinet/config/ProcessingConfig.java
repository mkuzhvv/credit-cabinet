package com.creditcabinet.config;

import com.creditcabinet.credit.processing.ProcessingProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProcessingProperties.class)
public class ProcessingConfig {

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService creditHistoryExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
