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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
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

        // 分段仅落库，ES 写入移到事务提交后（P1-2：embedding + 多次网络调用不得占长事务）
        List<KbSegmentDO> segments = buildSegments(kb);
        segments.forEach(kbSegmentMapper::insert);
        afterCommit(() -> indexSegmentsToEs(kb, segments));
        log.info("知识库创建并索引: id={}, title={}, 分段数={}", kb.getId(), kb.getTitle(), segments.size());
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
        // 重建分段与索引（P1-1）：记录旧分段 id，事务提交后先删 ES 旧文档再写新分段，避免陈旧文档累积
        List<Long> oldSegmentIds = kbSegmentMapper.selectList(
                        new LambdaQueryWrapper<KbSegmentDO>().eq(KbSegmentDO::getKbId, id))
                .stream().map(KbSegmentDO::getId).toList();
        kbSegmentMapper.delete(new LambdaQueryWrapper<KbSegmentDO>().eq(KbSegmentDO::getKbId, id));
        KnowledgeBaseDO full = requireKb(id);
        List<KbSegmentDO> newSegments = buildSegments(full);
        newSegments.forEach(kbSegmentMapper::insert);
        afterCommit(() -> {
            deleteSegmentsFromEs(oldSegmentIds);
            indexSegmentsToEs(full, newSegments);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireKb(id);
        knowledgeBaseMapper.deleteById(id);
        List<KbSegmentDO> segments = kbSegmentMapper.selectList(
                new LambdaQueryWrapper<KbSegmentDO>().eq(KbSegmentDO::getKbId, id));
        kbSegmentMapper.delete(new LambdaQueryWrapper<KbSegmentDO>().eq(KbSegmentDO::getKbId, id));
        // 事务提交后删除 ES 文档（ES 故障不影响 MySQL 主链路，由重试队列兜底）
        List<Long> segmentIds = segments.stream().map(KbSegmentDO::getId).toList();
        afterCommit(() -> deleteSegmentsFromEs(segmentIds));
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

    /** 内容切分段（纯内存，由调用方在事务内落库） */
    private List<KbSegmentDO> buildSegments(KnowledgeBaseDO kb) {
        List<String> parts = segmenter.segment(kb.getContent());
        List<KbSegmentDO> segments = new ArrayList<>(parts.size());
        int seq = 1;
        for (String part : parts) {
            KbSegmentDO segment = new KbSegmentDO();
            segment.setKbId(kb.getId());
            segment.setSeq(seq++);
            segment.setContent(part);
            segment.setCharCount(part.length());
            segment.setCreateTime(LocalDateTime.now());
            segment.setUpdateTime(LocalDateTime.now());
            segments.add(segment);
        }
        return segments;
    }

    /** 事务提交后执行回写（与 TicketServiceImpl.create 风格一致）：无事务环境（单测）直接执行 */
    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /** 写 ES 分段索引（事务提交后执行；失败进重试队列，不影响 MySQL 结果） */
    private void indexSegmentsToEs(KnowledgeBaseDO kb, List<KbSegmentDO> segments) {
        for (KbSegmentDO segment : segments) {
            try {
                knowledgeIndexService.indexSegment(segment.getId(), kb.getId(),
                        kb.getTitle(), kb.getCategory(), segment.getContent());
            } catch (Exception e) {
                log.error("ES 分段写入失败，进入重试队列: segmentId={}", segment.getId(), e);
                esSyncRetryProducer.sendRetry(segment.getId(), kb.getId(),
                        kb.getTitle(), kb.getCategory(), segment.getContent());
            }
        }
        log.info("知识库分段 ES 索引完成: kbId={}, 共 {} 段", kb.getId(), segments.size());
    }

    /** 删除旧分段 ES 文档（P1-1：重建分段时清理陈旧文档；失败进重试队列兜底最终一致） */
    private void deleteSegmentsFromEs(List<Long> segmentIds) {
        for (Long segmentId : segmentIds) {
            try {
                knowledgeIndexService.deleteSegment(segmentId);
            } catch (Exception e) {
                log.error("ES 分段删除失败，进入重试队列: segmentId={}", segmentId, e);
                esSyncRetryProducer.sendDeleteSegment(segmentId);
            }
        }
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
