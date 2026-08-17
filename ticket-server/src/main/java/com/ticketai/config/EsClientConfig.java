package com.ticketai.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ES 客户端配置（elasticsearch-java 官方客户端，DEV_DOC §5.4）。
 */
@Configuration
public class EsClientConfig {

    @Bean
    public ElasticsearchClient elasticsearchClient(@Value("${app.es.uris}") String uris) {
        RestClient restClient = RestClient.builder(HttpHost.create(uris)).build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
