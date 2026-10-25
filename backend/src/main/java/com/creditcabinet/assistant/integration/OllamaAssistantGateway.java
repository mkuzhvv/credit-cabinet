package com.creditcabinet.assistant.integration;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.creditcabinet.assistant.integration.AssistantGeneration.ModelMetadata;
import com.creditcabinet.assistant.model.AssistantContext;
import com.creditcabinet.assistant.service.AssistantException;
import com.creditcabinet.assistant.service.AssistantProperties;
import com.creditcabinet.assistant.service.AssistantReplyValidator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public class OllamaAssistantGateway implements AssistantGateway, AutoCloseable {

    private final HttpClient transport;
    private final RestClient http;
    private final OllamaChatModel model;
    private final JsonMapper mapper;
    private final AssistantModelSettings settings;
    private final AssistantPromptBuilder prompts;
    private final AssistantGrounding grounding;
    private final AssistantReplyValidator validator;
    private final ThreadLocal<UUID> requestIds = new ThreadLocal<>();

    public OllamaAssistantGateway(OllamaProperties connection, AssistantProperties properties, AssistantModelSettings settings,
            AssistantPromptBuilder prompts, AssistantGrounding grounding, AssistantReplyValidator validator, JsonMapper mapper) {
        this.settings = settings;
        this.prompts = prompts;
        this.grounding = grounding;
        this.validator = validator;
        this.mapper = mapper.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        this.transport = HttpClient.newBuilder().connectTimeout(connection.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        var requests = new JdkClientHttpRequestFactory(transport);
        requests.setReadTimeout(properties.timeout());
        var builder = RestClient.builder().baseUrl(connection.baseUrl().toString()).requestFactory(requests)
                .requestInterceptor((request, body, execution) -> {
                    UUID id = requestIds.get();
                    if (id != null) {
                        request.getHeaders().set("X-Request-Id", id.toString());
                    }
                    var response = execution.execute(request, body);
                    MediaType type = response.getHeaders().getContentType();
                    if (response.getStatusCode().is2xxSuccessful() && (response.getStatusCode().value() != 200
                            || type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type))) {
                        response.close();
                        throw AssistantException.invalidResponse();
                    }
                    return response;
                });
        this.http = builder.build();
        var api = OllamaApi.builder().baseUrl(connection.baseUrl().toString()).restClientBuilder(builder).build();
        this.model = OllamaChatModel.builder().ollamaApi(api).options(options().build())
                .retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build())).build();
    }

    @Override
    public AssistantGeneration generate(AssistantContext input, UUID requestId) {
        long started = System.nanoTime();
        requestIds.set(requestId);
        try {
            verifyModel();
            var prepared = prompts.build(input);
            var response = model.call(new Prompt(prepared.messages(), options().format(outputSchema(input)).build()));
            var result = response.getResult();
            if (!Boolean.TRUE.equals(response.getMetadata().get("done")) || !settings.model().equals(response.getMetadata().getModel())
                    || result == null || !"stop".equals(result.getMetadata().getFinishReason()) || result.getOutput().hasToolCalls()) {
                throw AssistantException.invalidResponse();
            }
            AssistantReply reply = mapper.readValue(result.getOutput().getText(), AssistantReply.class);
            ModelMetadata metadata = metadata(prepared, started, response.getMetadata().getUsage().getPromptTokens(),
                    response.getMetadata().getUsage().getCompletionTokens(), "model_text");
            validator.validate(input, new AssistantGeneration(reply, metadata));
            var grounded = grounding.apply(input, reply);
            var generation = new AssistantGeneration(grounded.reply(), metadata(prepared, started,
                    response.getMetadata().getUsage().getPromptTokens(), response.getMetadata().getUsage().getCompletionTokens(), grounded.rule()));
            validator.validate(input, generation);
            return generation;
        } catch (AssistantException ex) {
            throw ex;
        } catch (JacksonException ex) {
            throw AssistantException.invalidResponse();
        } catch (RuntimeException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                    throw AssistantException.timeout(ex);
                }
                if (cause instanceof JacksonException) {
                    throw AssistantException.invalidResponse();
                }
            }
            if (ex instanceof RestClientResponseException response && response.getStatusCode().value() == 400) {
                throw AssistantException.invalidResponse();
            }
            throw AssistantException.unavailable(ex);
        } finally {
            requestIds.remove();
        }
    }

    private void verifyModel() {
        var response = http.get().uri("/api/tags").accept(MediaType.APPLICATION_JSON).retrieve().toEntity(byte[].class);
        if (response.getStatusCode().value() != 200 || response.getHeaders().getContentType() == null
                || !MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType())) {
            throw AssistantException.unavailable();
        }
        var models = mapper.readTree(response.getBody()).path("models");
        for (var available : models) {
            if (settings.model().equals(available.path("name").asString())
                    && settings.modelDigest().equals(available.path("digest").asString())
                    && available.path("remote_host").asString("").isBlank()) {
                return;
            }
        }
        throw AssistantException.unavailable();
    }

    private OllamaChatOptions.Builder options() {
        return OllamaChatOptions.builder().model(settings.model()).numCtx(settings.numCtx()).numPredict(settings.numPredict())
                .temperature(settings.temperature()).seed(settings.seed()).disableThinking().keepAlive(settings.keepAlive())
                .presencePenalty(settings.presencePenalty()).repeatPenalty(settings.repeatPenalty()).truncate(false);
    }

    private Map<String, Object> outputSchema(AssistantContext input) {
        List<Object> codes = new ArrayList<>(input.context().catalog().stream().map(product -> (Object) product.code()).toList());
        codes.add(null);
        return Map.of("type", "object", "additionalProperties", false, "required", List.of("answer", "recommendedProductCode"),
                "properties", Map.of("answer", Map.of("type", "string", "minLength", 1, "maxLength", 3000),
                        "recommendedProductCode", Map.of("type", List.of("string", "null"), "enum", codes)));
    }

    private ModelMetadata metadata(AssistantPromptBuilder.PreparedPrompt prompt, long started, int promptTokens, int completionTokens, String rule) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("num_ctx", String.valueOf(settings.numCtx()));
        parameters.put("num_predict", String.valueOf(settings.numPredict()));
        parameters.put("temperature", String.valueOf(settings.temperature()));
        parameters.put("seed", String.valueOf(settings.seed()));
        parameters.put("think", "false");
        parameters.put("keep_alive", settings.keepAlive());
        parameters.put("presence_penalty", String.valueOf(settings.presencePenalty()));
        parameters.put("repeat_penalty", String.valueOf(settings.repeatPenalty()));
        parameters.put("framing_reserve_tokens", String.valueOf(settings.framingReserveTokens()));
        parameters.put("estimated_prompt_tokens", String.valueOf(prompt.tokens()));
        parameters.put("removed_history_pairs", String.valueOf(prompt.removedPairs()));
        parameters.put("grounding_version", AssistantGrounding.VERSION);
        parameters.put("grounding_rule", rule);
        parameters.put("prompt_tokens", String.valueOf(promptTokens));
        parameters.put("completion_tokens", String.valueOf(completionTokens));
        parameters.put("elapsed_ms", String.valueOf((System.nanoTime() - started) / 1_000_000));
        return new ModelMetadata(settings.model(), settings.modelDigest(), settings.promptVersion(), parameters);
    }

    @Override
    public void close() {
        transport.shutdownNow();
    }
}
