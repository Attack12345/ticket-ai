package com.ticketai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 自研 LLM 客户端（OpenAI 兼容协议，DEV_DOC §5.5.1）。
 * M5 实现 embedding；chat 接口 M6 扩展。
 */
@Slf4j
@Component
public class LlmClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.llm.base-url}")
    private String baseUrl;

    @Value("${app.llm.api-key}")
    private String apiKey;

    @Value("${app.llm.embed-model}")
    private String embedModel;

    @Value("${app.llm.timeout-ms}")
    private long timeoutMs;

    /**
     * 文本向量化。失败抛 LlmException（调用方降级为全文检索）。
     */
    public float[] embed(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("LLM_API_KEY 未配置", LlmException.NO_KEY);
        }
        try {
            String body = objectMapper.writeValueAsString(
                    java.util.Map.of("model", embedModel, "input", java.util.List.of(text)));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LlmException("embedding 服务错误: HTTP " + response.statusCode(), LlmException.SERVER_ERROR);
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embedding = root.path("data").get(0).path("embedding");
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            return vector;
        } catch (LlmException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new LlmException("embedding 超时", LlmException.TIMEOUT);
        } catch (Exception e) {
            throw new LlmException("embedding 调用异常: " + e.getMessage(), LlmException.SERVER_ERROR);
        }
    }
}
