package com.ticketai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketai.entity.AiUsageLogDO;
import com.ticketai.mapper.AiUsageLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 自研 LLM 客户端（OpenAI 兼容协议，DEV_DOC §5.5.1）。
 * embed / chatJson 均记录 ai_usage_log（成功/失败必写）。
 * LLM 慢/挂：单次调用上限取 LLM_SLA 与熔断硬上限的较小值；连续失败触发 LlmCircuitBreaker
 * 熔断（打开期间立即抛 CIRCUIT_OPEN），既有降级链路（规则/兜底）照常生效，不拖垮调用方。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiUsageLogMapper aiUsageLogMapper;
    private final LlmCircuitBreaker llmCircuitBreaker;

    @Value("${app.llm.base-url}")
    private String baseUrl;

    @Value("${app.llm.api-key}")
    private String apiKey;

    @Value("${app.llm.embed-model}")
    private String embedModel;

    @Value("${app.llm.chat-model}")
    private String chatModel;

    @Value("${app.llm.timeout-ms}")
    private long timeoutMs;

    /** 熔断层超时硬上限（TimeLimiter 的轻量替代，仅压慢/挂的请求，可被 env 覆盖） */
    @Value("${app.llm.circuit.timeout-ms:5000}")
    private long circuitTimeoutMs;

    /**
     * 文本向量化。失败抛 LlmException（调用方降级为全文检索）。
     */
    public float[] embed(String text) {
        long start = System.currentTimeMillis();
        if (apiKey == null || apiKey.isBlank()) {
            recordUsage(null, "EMBED", null, null, null, null, false, "LLM_API_KEY 未配置", start);
            throw new LlmException("LLM_API_KEY 未配置", LlmException.NO_KEY);
        }
        // 熔断打开：立即降级，不让慢/挂的 LLM 拖累调用方
        if (!llmCircuitBreaker.tryAcquire()) {
            recordUsage(null, "EMBED", embedModel, null, null, null, false, "熔断打开，快速降级", start);
            throw new LlmException("LLM 熔断打开，快速降级", LlmException.CIRCUIT_OPEN);
        }
        boolean ok = false;
        try {
            String body = objectMapper.writeValueAsString(
                    Map.of("model", embedModel, "input", List.of(text)));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofMillis(effectiveTimeout()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                recordUsage(null, "EMBED", embedModel, null, null, null, false,
                        "HTTP " + response.statusCode(), start);
                throw new LlmException("embedding 服务错误: HTTP " + response.statusCode(), LlmException.SERVER_ERROR);
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embedding = root.path("data").get(0).path("embedding");
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            JsonNode usage = root.path("usage");
            recordUsage(null, "EMBED", embedModel,
                    usage.path("prompt_tokens").asInt(-1),
                    usage.path("completion_tokens").asInt(0),
                    usage.path("total_tokens").asInt(-1), true, null, start);
            ok = true;
            return vector;
        } catch (LlmException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            recordUsage(null, "EMBED", embedModel, null, null, null, false, "超时", start);
            throw new LlmException("embedding 超时", LlmException.TIMEOUT);
        } catch (Exception e) {
            recordUsage(null, "EMBED", embedModel, null, null, null, false, e.getMessage(), start);
            throw new LlmException("embedding 调用异常: " + e.getMessage(), LlmException.SERVER_ERROR);
        } finally {
            // 成功复位失败窗口；失败（HTTP 错误/超时/解析异常）计入熔断
            if (ok) {
                llmCircuitBreaker.recordSuccess();
            } else {
                llmCircuitBreaker.recordFailure();
            }
        }
    }

    /**
     * 对话并强制 JSON 对象输出（response_format: json_object，DEV_DOC §5.5.1）。
     *
     * @return 模型返回的 JSON 字符串
     */
    public String chatJson(Long ticketId, String scene, List<Message> messages) {
        long start = System.currentTimeMillis();
        if (apiKey == null || apiKey.isBlank()) {
            recordUsage(ticketId, scene, null, null, null, null, false, "LLM_API_KEY 未配置", start);
            throw new LlmException("LLM_API_KEY 未配置", LlmException.NO_KEY);
        }
        // 熔断打开：立即降级，不让慢/挂的 LLM 拖累调用方
        if (!llmCircuitBreaker.tryAcquire()) {
            recordUsage(ticketId, scene, chatModel, null, null, null, false, "熔断打开，快速降级", start);
            throw new LlmException("LLM 熔断打开，快速降级", LlmException.CIRCUIT_OPEN);
        }
        boolean ok = false;
        try {
            Map<String, Object> payload = Map.of(
                    "model", chatModel,
                    "messages", messages.stream()
                            .map(m -> Map.of("role", m.role(), "content", m.content())).toList(),
                    "response_format", Map.of("type", "json_object"),
                    "temperature", 0.3);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofMillis(effectiveTimeout()))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                recordUsage(ticketId, scene, chatModel, null, null, null, false,
                        "HTTP " + response.statusCode(), start);
                throw new LlmException("chat 服务错误: HTTP " + response.statusCode(), LlmException.SERVER_ERROR);
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").get(0).path("message").path("content").asText();
            JsonNode usage = root.path("usage");
            recordUsage(ticketId, scene, chatModel,
                    usage.path("prompt_tokens").asInt(-1),
                    usage.path("completion_tokens").asInt(-1),
                    usage.path("total_tokens").asInt(-1), true, null, start);
            // 校验 JSON 合法性（非法抛 PARSE_ERROR，调用方降级）
            objectMapper.readTree(content);
            ok = true;
            return content;
        } catch (LlmException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            recordUsage(ticketId, scene, chatModel, null, null, null, false, "超时", start);
            throw new LlmException("chat 超时", LlmException.TIMEOUT);
        } catch (Exception e) {
            recordUsage(ticketId, scene, chatModel, null, null, null, false, e.getMessage(), start);
            throw new LlmException("chat 调用异常: " + e.getMessage(), LlmException.SERVER_ERROR);
        } finally {
            // 成功复位失败窗口；失败（HTTP 错误/超时/解析异常）计入熔断
            if (ok) {
                llmCircuitBreaker.recordSuccess();
            } else {
                llmCircuitBreaker.recordFailure();
            }
        }
    }

    /** 单次调用超时上限：取提供方 SLA 与熔断层硬上限的较小值，压住慢/挂请求 */
    private long effectiveTimeout() {
        return Math.min(timeoutMs, circuitTimeoutMs);
    }

    /** 记录 LLM 调用（DEV_DOC：每次调用必写 ai_usage_log） */
    private void recordUsage(Long ticketId, String scene, String model,
                             Integer promptTokens, Integer completionTokens, Integer totalTokens,
                             boolean success, String errorMsg, long startMs) {
        try {
            AiUsageLogDO usage = new AiUsageLogDO();
            usage.setTicketId(ticketId);
            usage.setScene(scene);
            usage.setModel(model);
            usage.setPromptTokens(promptTokens);
            usage.setCompletionTokens(completionTokens);
            usage.setTotalTokens(totalTokens);
            usage.setLatencyMs((int) (System.currentTimeMillis() - startMs));
            usage.setSuccess(success ? 1 : 0);
            usage.setErrorMsg(errorMsg);
            usage.setCreateTime(LocalDateTime.now());
            aiUsageLogMapper.insert(usage);
        } catch (Exception e) {
            log.error("ai_usage_log 写入失败: {}", e.getMessage());
        }
    }
}
