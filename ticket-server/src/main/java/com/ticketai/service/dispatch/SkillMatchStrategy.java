package com.ticketai.service.dispatch;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketai.entity.AgentDO;
import com.ticketai.entity.TicketDO;
import com.ticketai.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 技能匹配策略：工单 category 与坐席 skill_tags 匹配（DEV_DOC §5.3.1）。
 * category 为空或无匹配坐席时返回 null（交下一策略）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillMatchStrategy implements DispatchStrategy {

    private final AgentMapper agentMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return "SKILL_MATCH";
    }

    @Override
    public Long dispatch(TicketDO ticket) {
        if (ticket.getCategory() == null || ticket.getCategory().isBlank()) {
            return null;
        }
        List<AgentDO> online = agentMapper.selectList(new LambdaQueryWrapper<AgentDO>()
                .eq(AgentDO::getStatus, 1));
        for (AgentDO agent : online) {
            List<String> tags = readTags(agent.getSkillTags());
            if (tags.contains(ticket.getCategory())) {
                return agent.getId();
            }
        }
        return null;
    }

    private List<String> readTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("技能标签解析失败: {}", json);
            return List.of();
        }
    }
}
