package com.ticketai.task;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketai.entity.KbSegmentDO;
import com.ticketai.entity.KnowledgeBaseDO;
import com.ticketai.es.EsIndexInitializer;
import com.ticketai.mapper.KbSegmentMapper;
import com.ticketai.mapper.KnowledgeBaseMapper;
import com.ticketai.es.KnowledgeIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ES 双写补偿对账（DEV_DOC §4.2.6）：每 10 分钟对比 MySQL 与 ES 的 id 差集补写。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsSyncCompensationTask {

    private final KbSegmentMapper kbSegmentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeIndexService knowledgeIndexService;
    private final ElasticsearchClient esClient;

    @Scheduled(cron = "0 */10 * * * ?")
    public void reconcile() {
        try {
            // MySQL 全量分段
            List<KbSegmentDO> segments = kbSegmentMapper.selectList(null);
            Set<String> mysqlIds = segments.stream()
                    .map(s -> String.valueOf(s.getId())).collect(Collectors.toSet());
            // ES 已有 id
            Set<String> esIds = new HashSet<>();
            SearchRequest request = new SearchRequest.Builder()
                    .index(EsIndexInitializer.KB_INDEX)
                    .size(10_000)
                    .source(sr -> sr.filter(f -> f.includes("id")))
                    .build();
            @SuppressWarnings("unchecked")
            SearchResponse<Map<String, Object>> response =
                    (SearchResponse<Map<String, Object>>) (SearchResponse<?>) esClient.search(request, Map.class);
            response.hits().hits().forEach(h -> esIds.add(String.valueOf(h.source().get("id"))));

            // 差集补写
            Set<String> missing = new HashSet<>(mysqlIds);
            missing.removeAll(esIds);
            if (!missing.isEmpty()) {
                log.info("ES 补偿对账：发现 {} 条缺失分段，开始补写", missing.size());
                for (KbSegmentDO segment : segments) {
                    if (!missing.contains(String.valueOf(segment.getId()))) {
                        continue;
                    }
                    KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(segment.getKbId());
                    if (kb == null) {
                        continue;
                    }
                    try {
                        knowledgeIndexService.indexSegment(segment.getId(), kb.getId(),
                                kb.getTitle(), kb.getCategory(), segment.getContent());
                    } catch (Exception e) {
                        log.warn("补偿补写失败: segmentId={}", segment.getId(), e);
                    }
                }
            } else {
                log.info("ES 补偿对账：MySQL 与 ES 一致，无需补写");
            }
        } catch (Exception e) {
            log.error("ES 补偿对账异常（下次重试）", e);
        }
    }
}
