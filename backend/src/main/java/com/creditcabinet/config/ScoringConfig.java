package com.creditcabinet.config;

import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.creditcabinet.credit.decision.PolicyParameters;
import com.creditcabinet.credit.scoring.HttpRiskScoringClient;
import com.creditcabinet.credit.scoring.ScoringProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScoringProperties.class)
public class ScoringConfig {

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService scoringExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    HttpRiskScoringClient riskScoringClient(ScoringProperties properties, JsonMapper mapper,
            @Qualifier("scoringExecutor") ExecutorService executor) {
        return new HttpRiskScoringClient(properties, mapper, executor);
    }

    @Bean
    PolicyParameters creditPolicyParameters(ScoringProperties properties) {
        return new PolicyParameters("credit-cashflow-v1", 4, properties.expectedModelVersion(), new BigDecimal("0.50"));
    }
}
