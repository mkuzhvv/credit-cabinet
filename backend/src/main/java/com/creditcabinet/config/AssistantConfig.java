package com.creditcabinet.config;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.creditcabinet.assistant.integration.AssistantGateway;
import com.creditcabinet.assistant.integration.AssistantModelSettings;
import com.creditcabinet.assistant.integration.AssistantPromptBuilder;
import com.creditcabinet.assistant.integration.AssistantGrounding;
import com.creditcabinet.assistant.integration.OllamaAssistantGateway;
import com.creditcabinet.assistant.integration.OllamaProperties;
import com.creditcabinet.assistant.service.AssistantProperties;
import com.creditcabinet.assistant.service.AssistantReplyValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AssistantProperties.class, OllamaProperties.class})
public class AssistantConfig {

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService assistantExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConditionalOnMissingBean(AssistantGateway.class)
    AssistantGateway assistantGateway(OllamaProperties connection, AssistantProperties properties, AssistantModelSettings settings,
            AssistantPromptBuilder prompts, AssistantGrounding grounding, AssistantReplyValidator validator, JsonMapper mapper) {
        return new OllamaAssistantGateway(connection, properties, settings, prompts, grounding, validator, mapper);
    }

    @Bean
    AssistantModelSettings assistantModelSettings(JsonMapper mapper) throws IOException {
        return AssistantModelSettings.load(mapper);
    }

    @Bean
    AssistantPromptBuilder assistantPromptBuilder(JsonMapper mapper, AssistantModelSettings settings) throws IOException, NoSuchAlgorithmException {
        return new AssistantPromptBuilder(mapper, settings);
    }
}
