package com.ticketai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketai.common.exception.BusinessException;
import com.ticketai.common.exception.ErrorCode;
import com.ticketai.dto.AgentStatusDTO;
import com.ticketai.dto.AgentUpdateDTO;
import com.ticketai.entity.AgentDO;
import com.ticketai.entity.SkillGroupAgentDO;
import com.ticketai.mapper.AgentMapper;
import com.ticketai.mapper.SkillGroupAgentMapper;
import com.ticketai.service.AgentService;
import com.ticketai.vo.AgentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 坐席服务（DEV_DOC §6.5）。skillTags 以 JSON 数组字符串存储。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private final AgentMapper agentMapper;
    private final SkillGroupAgentMapper skillGroupAgentMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<AgentVO> list(Long groupId, Integer status) {
        LambdaQueryWrapper<AgentDO> wrapper = new LambdaQueryWrapper<AgentDO>()
                .eq(status != null, AgentDO::getStatus, status)
                .orderByAsc(AgentDO::getId);
        if (groupId != null) {
            List<Long> agentIds = skillGroupAgentMapper.selectList(new LambdaQueryWrapper<SkillGroupAgentDO>()
                            .eq(SkillGroupAgentDO::getGroupId, groupId)).stream()
                    .map(SkillGroupAgentDO::getAgentId).toList();
            if (agentIds.isEmpty()) {
                return List.of();
            }
            wrapper.in(AgentDO::getId, agentIds);
        }
        return agentMapper.selectList(wrapper).stream().map(this::toVO).toList();
    }

    @Override
    public void updateStatus(Long id, AgentStatusDTO dto) {
        AgentDO agent = requireAgent(id);
        agent.setStatus(dto.getStatus());
        agentMapper.updateById(agent);
        log.info("坐席状态变更: id={}, status={}", id, dto.getStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSkillTags(Long id, AgentUpdateDTO dto) {
        AgentDO agent = requireAgent(id);
        agent.setSkillTags(writeJson(dto.getSkillTags()));
        agentMapper.updateById(agent);
        log.info("坐席技能标签更新: id={}, tags={}", id, dto.getSkillTags());
    }

    private AgentDO requireAgent(Long id) {
        AgentDO agent = agentMapper.selectById(id);
        if (agent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "坐席不存在: id=" + id);
        }
        return agent;
    }

    private AgentVO toVO(AgentDO agent) {
        AgentVO vo = new AgentVO();
        vo.setId(agent.getId());
        vo.setUserId(agent.getUserId());
        vo.setName(agent.getName());
        vo.setStatus(agent.getStatus());
        vo.setCurrentLoad(agent.getCurrentLoad());
        vo.setSkillTags(readJson(agent.getSkillTags()));
        vo.setGroupIds(skillGroupAgentMapper.selectList(new LambdaQueryWrapper<SkillGroupAgentDO>()
                        .eq(SkillGroupAgentDO::getAgentId, agent.getId())).stream()
                .map(SkillGroupAgentDO::getGroupId).toList());
        return vo;
    }

    private String writeJson(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "技能标签序列化失败");
        }
    }

    private List<String> readJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("技能标签解析失败，按空处理: {}", json);
            return List.of();
        }
    }
}
