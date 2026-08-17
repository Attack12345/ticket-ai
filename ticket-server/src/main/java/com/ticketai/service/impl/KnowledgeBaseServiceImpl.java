package com.ticketai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketai.ai.LlmClient;
import com.ticketai.ai.LlmException;
import com.ticketai.common.PageResult;
import com.ticketai.common.exception.BusinessException;
import com.ticketai.common.exception.ErrorCode;
import com.ticketai.common.util.KnowledgeSegmenter;
import com.ticketai.dto.KbSearchDTO;
import com.ticketai.dto.KnowledgeBaseDTO;
import com.ticketai.entity.KbSegmentDO;
import com.ticketai.entity.KnowledgeBaseDO;
import com.ticketai.es.KnowledgeIndexService;
import com.ticketai.mapper.KbSegmentMapper;
import com.ticketai.mapper.KnowledgeBaseMapper;
import com.ticketai.service.KnowledgeBaseService;
import com.ticketai.vo.KbSearchHitVO;
import com.ticketai.vo.KnowledgeBaseVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 知识库服务（DEV_DOC §5.4）：CRUD + 分段 + ES 双写（同步失败发 MQ 重试，见 EsSyncRetryProducer）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KbSegmentMapper kbSegmentMapper;
    private final KnowledgeSegmenter segmenter;
    private final KnowledgeIndexService knowledgeIndexService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final com.ticketai.mq.EsSyncRetryProducer esSyncRetryProducer;

    @Override
    public PageResult<KnowledgeBaseVO> pageList(int page, int size, String keyword) {
        LambdaQueryWrapper<KnowledgeBaseDO> wrapper = new LambdaQueryWrapper<KnowledgeBaseDO>()
                .like(keyword != null && !keyword.isBlank(), KnowledgeBaseDO::getTitle, keyword)
                .orderByDesc(KnowledgeBaseDO::getCreateTime);
        Page<KnowledgeBaseDO> result = knowledgeBaseMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result.getRecords().stream().map(this::toVO).toList(),
                result.getTotal(), page, size);
    }

    @Override
    public KnowledgeBaseVO getDetail(Long id) {
        return toVO(requireKb(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(KnowledgeBaseDTO dto) {
        KnowledgeBaseDO kb = new KnowledgeBaseDO();
        kb.setTitle(dto.getTitle());
        kb.setCategory(dto.getCategory());
        kb.setContent(dto.getContent());
        kb.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        kb.setViewCount(0);
        kb.setCreateTime(LocalDateTime.now());
        kb.setUpdateTime(LocalDateTime.now());
        knowledgeBaseMapper.insert(kb);

        indexSegments(kb);
        log.info("知识库创建并索引: id={}, title={}, 分段数={}", kb.getId(), kb.getTitle(), kb.getContent().length() > 0 ? "见日志" : 0);
        return kb.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, KnowledgeBaseDTO dto) {
        requireKb(id);
        KnowledgeBaseDO kb = new KnowledgeBaseDO();
        kb.setId(id);
        kb.setTitle(dto.getTitle());
        kb.setCategory(dto.getCategory());
        kb.setContent(dto.getContent());
        kb.setStatus(dto.getStatus());
        kb.setUpdateTime(LocalDateTime.now());
        knowledgeBaseMapper.updateById(kb);
        // 重建分段与索引
        kbSegmentMapper.delete(new LambdaQueryWrapper<KbSegmentDO>().eq(KbSegmentDO::getKbId, id));
        KnowledgeBaseDO full = requireKb(id);
        indexSegments(full);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireKb(id);
        knowledgeBaseMapper.deleteById(id);
        List<KbSegmentDO> segments = kbSegmentMapper.selectList(
                new LambdaQueryWrapper<KbSegmentDO>().eq(KbSegmentDO::getKbId, id));
        kbSegmentMapper.delete(new LambdaQueryWrapper<KbSegmentDO>().eq(KbSegmentDO::getKbId, id));
        for (KbSegmentDO segment : segments) {
            try {
                knowledgeIndexService.deleteSegment(segment.getId());
            } catch (Exception e) {
                log.warn("ES 分段删除失败（将由补偿对账处理）: segmentId={}", segment.getId());
            }
        }
    }

    @Override
    public List<KbSearchHitVO> search(KbSearchDTO dto) {
        float[] vector = null;
        if (Boolean.TRUE.equals(dto.getSemantic())) {
            try {
                vector = llmClient.embed(dto.getKeyword());
            } catch (LlmException e) {
                log.debug("embedding 不可用，降级纯全文检索: {}", e.getReason());
            }
        }
        List<co.elastic.clients.elasticsearch.core.search.Hit<Map<String, Object>>> hits =
                knowledgeIndexService.search(dto.getKeyword(), vector, dto.getTopN() == null ? 5 : dto.getTopN());
        return hits.stream().map(hit -> {
            Map<String, Object> source = hit.source();
            KbSearchHitVO vo = new KbSearchHitVO();
            vo.setSegmentId(Long.valueOf(String.valueOf(source.get("id"))));
            vo.setKbId(source.get("kb_id") == null ? null : ((Number) source.get("kb_id")).longValue());
            vo.setTitle((String) source.get("title"));
            vo.setCategory((String) source.get("category"));
            vo.setContent((String) source.get("content"));
            vo.setScore(hit.score());
            return vo;
        }).toList();
    }

    // ---------- 私有 ----------

    /** 分段落库 + 写 ES 索引 */
    private void indexSegments(KnowledgeBaseDO kb) {
        List<String> parts = segmenter.segment(kb.getContent());
        int seq = 1;
        for (String part : parts) {
            KbSegmentDO segment = new KbSegmentDO();
            segment.setKbId(kb.getId());
            segment.setSeq(seq++);
            segment.setContent(part);
            segment.setCharCount(part.length());
            segment.setCreateTime(LocalDateTime.now());
            segment.setUpdateTime(LocalDateTime.now());
            kbSegmentMapper.insert(segment);
            try {
                knowledgeIndexService.indexSegment(segment.getId(), kb.getId(),
                        kb.getTitle(), kb.getCategory(), part);
            } catch (Exception e) {
                log.warn("ES 分段写入失败，进入重试队列: segmentId={}", segment.getId(), e);
                esSyncRetryProducer.sendRetry(segment.getId(), kb.getId(),
                        kb.getTitle(), kb.getCategory(), part);
            }
        }
        log.info("知识库分段完成: kbId={}, 共 {} 段", kb.getId(), parts.size());
    }

    private KnowledgeBaseDO requireKb(Long id) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(id);
        if (kb == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文章不存在: id=" + id);
        }
        return kb;
    }

    private KnowledgeBaseVO toVO(KnowledgeBaseDO kb) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        vo.setId(kb.getId());
        vo.setTitle(kb.getTitle());
        vo.setCategory(kb.getCategory());
        vo.setContent(kb.getContent());
        vo.setStatus(kb.getStatus());
        vo.setViewCount(kb.getViewCount());
        vo.setCreateTime(kb.getCreateTime());
        vo.setUpdateTime(kb.getUpdateTime());
        return vo;
    }
}
