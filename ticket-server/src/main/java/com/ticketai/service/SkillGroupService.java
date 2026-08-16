package com.ticketai.service;

import com.ticketai.dto.SkillGroupAgentsDTO;
import com.ticketai.dto.SkillGroupDTO;
import com.ticketai.vo.SkillGroupVO;

import java.util.List;

public interface SkillGroupService {

    List<SkillGroupVO> list();

    Long create(SkillGroupDTO dto);

    void update(Long id, SkillGroupDTO dto);

    void delete(Long id);

    /** 批量设置组内坐席（先删后插） */
    void setAgents(Long groupId, SkillGroupAgentsDTO dto);
}
