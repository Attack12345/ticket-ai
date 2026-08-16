package com.ticketai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketai.common.exception.BusinessException;
import com.ticketai.common.exception.ErrorCode;
import com.ticketai.dto.SkillGroupAgentsDTO;
import com.ticketai.dto.SkillGroupDTO;
import com.ticketai.entity.SkillGroupAgentDO;
import com.ticketai.entity.SkillGroupDO;
import com.ticketai.mapper.SkillGroupAgentMapper;
import com.ticketai.mapper.SkillGroupMapper;
import com.ticketai.service.SkillGroupService;
import com.ticketai.vo.SkillGroupVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 技能组服务（DEV_DOC §6.5）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillGroupServiceImpl implements SkillGroupService {

    private final SkillGroupMapper skillGroupMapper;
    private final SkillGroupAgentMapper skillGroupAgentMapper;

    @Override
    public List<SkillGroupVO> list() {
        return skillGroupMapper.selectList(new LambdaQueryWrapper<SkillGroupDO>()
                        .orderByAsc(SkillGroupDO::getId)).stream()
                .map(this::toVO).toList();
    }

    @Override
    public Long create(SkillGroupDTO dto) {
        SkillGroupDO group = new SkillGroupDO();
        group.setName(dto.getName());
        group.setDescription(dto.getDescription());
        group.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        skillGroupMapper.insert(group);
        return group.getId();
    }

    @Override
    public void update(Long id, SkillGroupDTO dto) {
        SkillGroupDO group = requireGroup(id);
        group.setName(dto.getName());
        group.setDescription(dto.getDescription());
        if (dto.getStatus() != null) {
            group.setStatus(dto.getStatus());
        }
        skillGroupMapper.updateById(group);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireGroup(id);
        skillGroupMapper.deleteById(id);
        // 级联清理关联关系
        skillGroupAgentMapper.delete(new LambdaQueryWrapper<SkillGroupAgentDO>()
                .eq(SkillGroupAgentDO::getGroupId, id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setAgents(Long groupId, SkillGroupAgentsDTO dto) {
        requireGroup(groupId);
        skillGroupAgentMapper.delete(new LambdaQueryWrapper<SkillGroupAgentDO>()
                .eq(SkillGroupAgentDO::getGroupId, groupId));
        for (Long agentId : dto.getAgentIds()) {
            SkillGroupAgentDO relation = new SkillGroupAgentDO();
            relation.setGroupId(groupId);
            relation.setAgentId(agentId);
            skillGroupAgentMapper.insert(relation);
        }
        log.info("技能组坐席设置: groupId={}, agents={}", groupId, dto.getAgentIds());
    }

    private SkillGroupDO requireGroup(Long id) {
        SkillGroupDO group = skillGroupMapper.selectById(id);
        if (group == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "技能组不存在: id=" + id);
        }
        return group;
    }

    private SkillGroupVO toVO(SkillGroupDO group) {
        SkillGroupVO vo = new SkillGroupVO();
        vo.setId(group.getId());
        vo.setName(group.getName());
        vo.setDescription(group.getDescription());
        vo.setStatus(group.getStatus());
        vo.setAgentCount(skillGroupAgentMapper.selectCount(new LambdaQueryWrapper<SkillGroupAgentDO>()
                .eq(SkillGroupAgentDO::getGroupId, group.getId())));
        return vo;
    }
}
