package com.ticketai.service.impl;

import com.ticketai.dto.SkillGroupAgentsDTO;
import com.ticketai.dto.SkillGroupDTO;
import com.ticketai.entity.SkillGroupAgentDO;
import com.ticketai.entity.SkillGroupDO;
import com.ticketai.mapper.SkillGroupAgentMapper;
import com.ticketai.mapper.SkillGroupMapper;
import com.ticketai.vo.SkillGroupVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 技能组 CRUD 测试（DEV_DOC M2 DoD：技能组接口 CRUD 测试通过）。
 */
@ExtendWith(MockitoExtension.class)
class SkillGroupServiceImplTest {

    @Mock
    private SkillGroupMapper skillGroupMapper;
    @Mock
    private SkillGroupAgentMapper skillGroupAgentMapper;

    private SkillGroupServiceImpl newService() {
        return new SkillGroupServiceImpl(skillGroupMapper, skillGroupAgentMapper);
    }

    @Test
    @DisplayName("创建技能组")
    void create() {
        when(skillGroupMapper.insert(any(SkillGroupDO.class))).thenAnswer(inv -> {
            inv.<SkillGroupDO>getArgument(0).setId(3L);
            return 1;
        });
        SkillGroupDTO dto = new SkillGroupDTO();
        dto.setName("售后组");
        dto.setDescription("售后问题");

        Long id = newService().create(dto);

        assertEquals(3L, id);
    }

    @Test
    @DisplayName("删除技能组：级联清理组内坐席关联")
    void deleteCascades() {
        SkillGroupDO group = new SkillGroupDO();
        group.setId(1L);
        when(skillGroupMapper.selectById(1L)).thenReturn(group);

        newService().delete(1L);

        verify(skillGroupMapper).deleteById(1L);
        verify(skillGroupAgentMapper).delete(any());
    }

    @Test
    @DisplayName("批量设置坐席：先删后插")
    void setAgents() {
        SkillGroupDO group = new SkillGroupDO();
        group.setId(1L);
        when(skillGroupMapper.selectById(1L)).thenReturn(group);

        SkillGroupAgentsDTO dto = new SkillGroupAgentsDTO();
        dto.setAgentIds(List.of(10L, 11L, 12L));
        newService().setAgents(1L, dto);

        verify(skillGroupAgentMapper).delete(any());
        verify(skillGroupAgentMapper, times(3)).insert(any(SkillGroupAgentDO.class));
    }

    @Test
    @DisplayName("列表带坐席数")
    void listWithCount() {
        SkillGroupDO group = new SkillGroupDO();
        group.setId(1L);
        group.setName("售后组");
        when(skillGroupMapper.selectList(any())).thenReturn(List.of(group));
        when(skillGroupAgentMapper.selectCount(any())).thenReturn(2L);

        List<SkillGroupVO> list = newService().list();

        assertEquals(1, list.size());
        assertEquals(2L, list.get(0).getAgentCount());
        assertNotNull(list.get(0).getName());
    }
}
