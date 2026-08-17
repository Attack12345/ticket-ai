package com.ticketai.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.ticketai.ai.LlmClient;
import com.ticketai.ai.LlmException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库 ES 索引读写（DEV_DOC §5.4.2/5.4.3）。
 * embedding 不可用时：写入跳过向量字段，检索降级纯全文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeIndexService {

    private final ElasticsearchClient esClient;
    private final LlmClient llmClient;

    /** 写入分段（upsert by id）。embedding 失败仅记录，不影响写入。 */
    public void indexSegment(Long segmentId, Long kbId, String title, String category, String content)
            throws java.io.IOException {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", String.valueOf(segmentId));
        doc.put("kb_id", kbId);
        doc.put("title", title);
        doc.put("category", category);
        doc.put("content", content);
        try {
            doc.put("content_vector", toFloatList(llmClient.embed(content)));
        } catch (LlmException e) {
            log.debug("embedding 不可用，跳过向量字段: {}", e.getReason());
        }
        IndexRequest<Map<String, Object>> request = IndexRequest.of(r -> r
                .index(EsIndexInitializer.KB_INDEX)
                .id(String.valueOf(segmentId))
                .document(doc));
        esClient.index(request);
    }

    public void deleteSegment(Long segmentId) throws java.io.IOException {
        esClient.delete(d -> d.index(EsIndexInitializer.KB_INDEX).id(String.valueOf(segmentId)));
    }

    /**
     * 检索：全文 match + 可选顶层 knn（ES 自动合并分数，文档 §5.4.3）。
     */
    public List<Hit<Map<String, Object>>> search(String keyword, float[] queryVector, int topN) {
        boolean vector = queryVector != null && queryVector.length > 0;
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(EsIndexInitializer.KB_INDEX)
                .size(topN)
                .query(q -> q.bool(b -> {
                    b.should(s -> s.match(m -> m.field("content").query(keyword)));
                    b.should(s -> s.match(m -> m.field("title").query(keyword)));
                    return b;
                }));
        if (vector) {
            builder.knn(k -> k.field("content_vector")
                    .queryVector(toFloatList(queryVector)).k(topN));
        }
        try {
            @SuppressWarnings("unchecked")
            SearchResponse<Map<String, Object>> response =
                    (SearchResponse<Map<String, Object>>) (SearchResponse<?>) esClient.search(builder.build(), Map.class);
            return response.hits().hits();
        } catch (Exception e) {
            log.error("ES 检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Float> toFloatList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float v : array) {
            list.add(v);
        }
        return list;
    }
}
