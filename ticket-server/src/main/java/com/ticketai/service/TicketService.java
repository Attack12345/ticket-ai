package com.ticketai.service;

import com.ticketai.common.PageResult;
import com.ticketai.dto.AcceptCategoryDTO;
import com.ticketai.dto.TicketCreateDTO;
import com.ticketai.dto.TicketReplyDTO;
import com.ticketai.entity.TicketDO;
import com.ticketai.query.TicketQuery;
import com.ticketai.state.TicketEvent;
import com.ticketai.vo.TicketStatusLogVO;
import com.ticketai.vo.TicketVO;

import java.util.List;

public interface TicketService {

    /** 创建工单（SUBMIT，创建即进入待分派） */
    TicketDO create(TicketCreateDTO dto);

    /** 分页查询 */
    PageResult<TicketVO> pageList(TicketQuery query);

    /** 详情（含 allowedEvents） */
    TicketVO getDetail(Long id);

    /** 状态流转唯一入口：校验 → 权限 → 乐观锁 → 状态日志 → 事件后置动作 */
    TicketDO transition(Long ticketId, TicketEvent event, Long operatorId, String operatorType);

    /** 状态流转（带分派后置参数：AUTO_ASSIGN/MANUAL_ASSIGN 时写入 agent/group/strategy） */
    TicketDO transition(Long ticketId, TicketEvent event, Long operatorId, String operatorType,
                        Long agentId, Long groupId, String assignStrategy);

    /** 抢单（Redisson 锁 + 显式乐观锁双保险，DEV_DOC §5.3.2） */
    TicketDO claim(Long ticketId);

    /** 回复（transition REPLY + 写评论） */
    TicketDO reply(Long ticketId, TicketReplyDTO dto);

    /** 采纳 AI 分类 */
    void acceptCategory(Long ticketId, AcceptCategoryDTO dto);

    /** 时间线：状态流转日志 */
    List<TicketStatusLogVO> timeline(Long ticketId);
}
