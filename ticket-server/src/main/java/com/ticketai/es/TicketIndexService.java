package com.ticketai.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.ticketai.ai.LlmClient;
import com.ticketai.ai.LlmException;
import com.ticketai.entity.TicketDO;
import com.ticketai.event.TicketResolvedEvent;
import com.ticketai.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 已解决工单索引（ticket_index，DEV_DOC §5.4.2）：RESOLVED 时异步写入，供相似工单召回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketIndexService {

    private final ElasticsearchClient esClient;
    private final LlmClient llmClient;
    private final TicketMapper ticketMapper;

    @EventListener
    @Async("dispatchExecutor")
    public void onTicketResolved(TicketResolvedEvent event) {
        TicketDO ticket = ticketMapper.selectById(event.ticketId());
        if (ticket == null) {
            return;
        }
        try {
            Map<String, Object> doc = new HashMap<>();
            doc.put("id", String.valueOf(ticket.getId()));
            doc.put("ticket_no", ticket.getTicketNo());
            doc.put("title", ticket.getTitle());
            doc.put("description", ticket.getDescription());
            doc.put("category", ticket.getCategory() == null ? "" : ticket.getCategory());
            doc.put("content", (ticket.getTitle() == null ? "" : ticket.getTitle())
                    + "\n" + (ticket.getDescription() == null ? "" : ticket.getDescription()));
            try {
                doc.put("content_vector", toFloatList(llmClient.embed(doc.get("content").toString())));
            } catch (LlmException e) {
                log.debug("相似工单 embedding 不可用，跳过向量: {}", e.getReason());
            }
            IndexRequest<Map<String, Object>> request = IndexRequest.of(r -> r
                    .index(EsIndexInitializer.TICKET_INDEX)
                    .id(String.valueOf(ticket.getId()))
                    .document(doc));
            esClient.index(request);
            log.info("已解决工单写入索引: ticketId={}", ticket.getId());
        } catch (Exception e) {
            log.warn("相似工单索引写入失败（不影响主流程）: ticketId={}", event.ticketId(), e);
        }
    }

    /** 相似工单召回（knn，无向量时返回空） */
    public List<Hit<Map<String, Object>>> similarTickets(float[] queryVector, int topN) {
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        try {
            SearchRequest request = new SearchRequest.Builder()
                    .index(EsIndexInitializer.TICKET_INDEX)
                    .size(topN)
                    .knn(k -> k.field("content_vector").queryVector(toFloatList(queryVector)).k(topN))
                    .build();
            @SuppressWarnings("unchecked")
            SearchResponse<Map<String, Object>> response =
                    (SearchResponse<Map<String, Object>>) (SearchResponse<?>) esClient.search(request, Map.class);
            return response.hits().hits();
        } catch (Exception e) {
            log.warn("相似工单召回失败: {}", e.getMessage());
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
