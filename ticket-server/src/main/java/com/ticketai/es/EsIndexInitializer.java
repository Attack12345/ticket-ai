package com.ticketai.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * ES 索引初始化（DEV_DOC §5.4.1）：启动时确保索引存在。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsIndexInitializer implements CommandLineRunner {

    public static final String KB_INDEX = "kb_segment_index";
    public static final String TICKET_INDEX = "ticket_index";

    private final ElasticsearchClient esClient;

    @Value("${app.llm.embed-dims}")
    private int embedDims;

    @Override
    public void run(String... args) {
        try {
            ensureIndex(KB_INDEX, true);
            ensureIndex(TICKET_INDEX, false);
        } catch (Exception e) {
            log.error("ES 索引初始化失败（检索功能不可用，系统其余功能不受影响）", e);
        }
    }

    private void ensureIndex(String index, boolean withTitle) throws Exception {
        if (esClient.indices().exists(e -> e.index(index)).value()) {
            log.info("ES 索引已存在: {}", index);
            return;
        }
        CreateIndexRequest.Builder builder = new CreateIndexRequest.Builder()
                .index(index)
                .mappings(m -> m.properties("id", p -> p.keyword(k -> k))
                        .properties("kb_id", p -> p.long_(l -> l))
                        .properties("category", p -> p.keyword(k -> k))
                        .properties("content", p -> p.text(t -> t)));
        if (withTitle) {
            builder.mappings(m -> m.properties("title", p -> p.text(t -> t)));
        }
        builder.mappings(m -> m.properties("content_vector", p -> p.denseVector(d -> d
                .dims(embedDims)
                .index(true)
                .similarity("cosine"))));
        esClient.indices().create(builder.build());
        log.info("ES 索引创建完成: {}", index);
    }
}
