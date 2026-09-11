package com.ticketai.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketai.entity.AgentDO;
import com.ticketai.entity.SkillGroupAgentDO;
import com.ticketai.entity.SkillGroupDO;
import com.ticketai.entity.SysPermissionDO;
import com.ticketai.entity.SysRoleDO;
import com.ticketai.entity.SysRolePermissionDO;
import com.ticketai.entity.SysUserDO;
import com.ticketai.entity.SysUserRoleDO;
import com.ticketai.mapper.AgentMapper;
import com.ticketai.mapper.SkillGroupAgentMapper;
import com.ticketai.mapper.SkillGroupMapper;
import com.ticketai.mapper.SysPermissionMapper;
import com.ticketai.mapper.SysRoleMapper;
import com.ticketai.mapper.SysRolePermissionMapper;
import com.ticketai.mapper.SysUserMapper;
import com.ticketai.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 初始数据初始化（DEV_DOC schema.sql 说明）：
 * 演示账号（admin/agent01）仅按开关播种（默认开；生产 app.init.seed-demo-accounts=false 关闭）；
 * 角色-权限绑定为生产必要，始终执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    /** 演示账号统一初始密码（仅开发环境使用；禁止在日志中明文输出） */
    private static final String DEFAULT_PASSWORD = "Admin@12345";

    /** 生产播种演示账号会产生永久后门账号，必须通过 app.init.seed-demo-accounts=false 关闭 */
    @Value("${app.init.seed-demo-accounts:true}")
    private boolean seedDemoAccounts;

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysPermissionMapper sysPermissionMapper;
    private final AgentMapper agentMapper;
    private final SkillGroupMapper skillGroupMapper;
    private final SkillGroupAgentMapper skillGroupAgentMapper;

    @Override
    public void run(String... args) {
        if (Boolean.TRUE.equals(seedDemoAccounts)) {
            initDemoAccounts();
        } else {
            log.info("演示账号播种已关闭（app.init.seed-demo-accounts=false）");
        }
        initRolePermissions();
        if (Boolean.TRUE.equals(seedDemoAccounts)) {
            initAgentProfiles();
        }
    }

    /** 仅开发环境：播种 admin / agent01 演示账号（生产关闭） */
    private void initDemoAccounts() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String encoded = encoder.encode(DEFAULT_PASSWORD);
        LocalDateTime now = LocalDateTime.now();

        if (sysUserMapper.selectCount(new LambdaQueryWrapper<>()) == 0) {
            createUser("admin", "管理员", encoded, now, "ADMIN");
            createUser("agent01", "坐席一号", encoded, now, "AGENT");
            // 安全红线：日志禁止输出明文口令
            log.info("初始化完成：创建演示账号 admin / agent01（初始密码见 README，仅开发环境，生产请在初始化后立即改密）");
        } else {
            log.info("初始化跳过：sys_user 已有数据");
        }
    }

    /** 坐席档案初始化（幂等）：agent01 用户 → agent 档案，并加入售后组 */
    private void initAgentProfiles() {
        if (agentMapper.selectCount(new LambdaQueryWrapper<>()) > 0) {
            return;
        }
        SysUserDO agentUser = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUserDO>()
                .eq(SysUserDO::getUsername, "agent01"));
        if (agentUser == null) {
            log.warn("坐席初始化跳过：agent01 用户不存在");
            return;
        }
        AgentDO agent = new AgentDO();
        agent.setUserId(agentUser.getId());
        agent.setName("坐席一号");
        agent.setStatus(1);
        agent.setCurrentLoad(0);
        agent.setSkillTags("[\"售后\",\"投诉\"]");
        agent.setCreateBy("system");
        agent.setCreateTime(LocalDateTime.now());
        agent.setUpdateTime(LocalDateTime.now());
        agentMapper.insert(agent);

        SkillGroupDO afterSale = skillGroupMapper.selectOne(new LambdaQueryWrapper<SkillGroupDO>()
                .eq(SkillGroupDO::getName, "售后组"));
        if (afterSale != null) {
            SkillGroupAgentDO relation = new SkillGroupAgentDO();
            relation.setGroupId(afterSale.getId());
            relation.setAgentId(agent.getId());
            skillGroupAgentMapper.insert(relation);
        }
        log.info("坐席档案初始化完成：agent01 -> agent id={}", agent.getId());
    }

    /** 角色-权限绑定（幂等）：ADMIN 绑全部权限，AGENT 绑坐席权限 */
    private void initRolePermissions() {
        if (sysRolePermissionMapper.selectCount(new LambdaQueryWrapper<>()) > 0) {
            return;
        }
        List<SysPermissionDO> allPermissions = sysPermissionMapper.selectList(null);
        if (allPermissions.isEmpty()) {
            log.warn("角色权限初始化跳过：sys_permission 为空");
            return;
        }
        bindRolePermissions("ADMIN", allPermissions.stream().map(SysPermissionDO::getId).toList());

        List<String> agentPermCodes = List.of(
                "ticket:view", "ticket:claim", "ticket:reply",
                "ticket:resolve", "ticket:close", "ticket:escalate", "ticket:edit",
                "dashboard:view");
        List<Long> agentPermIds = allPermissions.stream()
                .filter(p -> agentPermCodes.contains(p.getCode()))
                .map(SysPermissionDO::getId).toList();
        bindRolePermissions("AGENT", agentPermIds);
        log.info("角色权限初始化完成：ADMIN={} 条, AGENT={} 条",
                allPermissions.size(), agentPermIds.size());
    }

    private void bindRolePermissions(String roleCode, List<Long> permissionIds) {
        SysRoleDO role = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRoleDO>().eq(SysRoleDO::getCode, roleCode));
        if (role == null || permissionIds.isEmpty()) {
            return;
        }
        for (Long permissionId : permissionIds) {
            SysRolePermissionDO rp = new SysRolePermissionDO();
            rp.setRoleId(role.getId());
            rp.setPermissionId(permissionId);
            try {
                sysRolePermissionMapper.insert(rp);
            } catch (DuplicateKeyException e) {
                // P1-12：多副本并发首启，uk 唯一键兜底（已存在则跳过）
                log.debug("角色-权限已存在，跳过: roleId={}, permissionId={}", role.getId(), permissionId);
            }
        }
    }

    private void createUser(String username, String nickname, String encodedPwd, LocalDateTime now, String roleCode) {
        SysUserDO user = new SysUserDO();
        user.setUsername(username);
        user.setPassword(encodedPwd);
        user.setNickname(nickname);
        user.setStatus(1);
        user.setCreateBy("system");
        user.setCreateTime(now);
        user.setUpdateTime(now);
        try {
            sysUserMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // P1-12：uk_username 唯一键兜底并发首启，查回已存在用户绑定角色
            SysUserDO existed = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUserDO>()
                    .eq(SysUserDO::getUsername, username));
            if (existed != null) {
                user = existed;
            } else {
                throw e;
            }
        }

        SysRoleDO role = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRoleDO>().eq(SysRoleDO::getCode, roleCode));
        if (role != null) {
            SysUserRoleDO userRole = new SysUserRoleDO();
            userRole.setUserId(user.getId());
            userRole.setRoleId(role.getId());
            try {
                sysUserRoleMapper.insert(userRole);
            } catch (DuplicateKeyException e) {
                log.debug("用户-角色已存在，跳过: userId={}, roleId={}", user.getId(), role.getId());
            }
        }
    }
}
