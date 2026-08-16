package com.ticketai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketai.common.exception.BusinessException;
import com.ticketai.common.exception.ErrorCode;
import com.ticketai.dto.SlaPolicyDTO;
import com.ticketai.entity.SlaPolicyDO;
import com.ticketai.mapper.SlaPolicyMapper;
import com.ticketai.service.SlaPolicyService;
import com.ticketai.vo.SlaPolicyVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SLA 策略管理（DEV_DOC §5.2）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlaPolicyServiceImpl implements SlaPolicyService {

    private final SlaPolicyMapper slaPolicyMapper;

    @Override
    public List<SlaPolicyVO> list() {
        return slaPolicyMapper.selectList(new LambdaQueryWrapper<SlaPolicyDO>()
                        .orderByAsc(SlaPolicyDO::getPriority)).stream()
                .map(this::toVO).toList();
    }

    @Override
    public Long create(SlaPolicyDTO dto) {
        checkPriorityUnique(dto.getPriority(), null);
        SlaPolicyDO policy = new SlaPolicyDO();
        applyDto(policy, dto);
        slaPolicyMapper.insert(policy);
        log.info("SLA 策略创建: id={}, priority={}", policy.getId(), dto.getPriority());
        return policy.getId();
    }

    @Override
    public void update(Long id, SlaPolicyDTO dto) {
        requirePolicy(id);
        checkPriorityUnique(dto.getPriority(), id);
        SlaPolicyDO policy = new SlaPolicyDO();
        policy.setId(id);
        applyDto(policy, dto);
        slaPolicyMapper.updateById(policy);
    }

    @Override
    public void delete(Long id) {
        requirePolicy(id);
        slaPolicyMapper.deleteById(id);
    }

    private void checkPriorityUnique(Integer priority, Long excludeId) {
        LambdaQueryWrapper<SlaPolicyDO> wrapper = new LambdaQueryWrapper<SlaPolicyDO>()
                .eq(SlaPolicyDO::getPriority, priority);
        if (excludeId != null) {
            wrapper.ne(SlaPolicyDO::getId, excludeId);
        }
        if (slaPolicyMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该优先级已存在策略，请勿重复配置");
        }
    }

    private void applyDto(SlaPolicyDO policy, SlaPolicyDTO dto) {
        policy.setName(dto.getName());
        policy.setPriority(dto.getPriority());
        policy.setFirstResponseMinutes(dto.getFirstResponseMinutes());
        policy.setResolveMinutes(dto.getResolveMinutes());
        policy.setAutoEscalate(dto.getAutoEscalate() == null ? 1 : dto.getAutoEscalate());
        policy.setEscalateAction(dto.getEscalateAction());
        policy.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        policy.setRemark(dto.getRemark());
    }

    private SlaPolicyDO requirePolicy(Long id) {
        SlaPolicyDO policy = slaPolicyMapper.selectById(id);
        if (policy == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "SLA 策略不存在: id=" + id);
        }
        return policy;
    }

    private SlaPolicyVO toVO(SlaPolicyDO policy) {
        SlaPolicyVO vo = new SlaPolicyVO();
        vo.setId(policy.getId());
        vo.setName(policy.getName());
        vo.setPriority(policy.getPriority());
        vo.setFirstResponseMinutes(policy.getFirstResponseMinutes());
        vo.setResolveMinutes(policy.getResolveMinutes());
        vo.setAutoEscalate(policy.getAutoEscalate());
        vo.setEscalateAction(policy.getEscalateAction());
        vo.setStatus(policy.getStatus());
        vo.setRemark(policy.getRemark());
        return vo;
    }
}
