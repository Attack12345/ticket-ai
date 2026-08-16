package com.ticketai.service;

import com.ticketai.dto.AgentStatusDTO;
import com.ticketai.dto.AgentUpdateDTO;
import com.ticketai.vo.AgentVO;

import java.util.List;

public interface AgentService {

    /** 坐席列表（可按技能组、在线状态过滤） */
    List<AgentVO> list(Long groupId, Integer status);

    /** 上下线 */
    void updateStatus(Long id, AgentStatusDTO dto);

    /** 更新技能标签 */
    void updateSkillTags(Long id, AgentUpdateDTO dto);
}
