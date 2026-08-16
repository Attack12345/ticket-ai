-- ============================================================
-- TicketAI 企业智能客服工单系统 — 权威建表脚本 v1.0
-- 数据库: ticket_ai  (MySQL 8.0, utf8mb4)
-- 说明: 本文档是唯一权威 DDL。AI 开发过程中禁止修改字段名/类型/注释。
--       如需变更 → 停止并询问人类。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `ticket_ai` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `ticket_ai`;

-- ------------------------------------------------------------
-- 1. 系统用户（登录账号）
-- ------------------------------------------------------------
CREATE TABLE `sys_user` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username`    varchar(50)  NOT NULL COMMENT '登录名',
  `password`    varchar(100) NOT NULL COMMENT 'BCrypt 密码',
  `nickname`    varchar(50)  DEFAULT NULL COMMENT '昵称',
  `phone`       varchar(20)  DEFAULT NULL COMMENT '手机号',
  `status`      tinyint      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
  `create_by`   varchar(64)  DEFAULT NULL COMMENT '创建人',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`   varchar(64)  DEFAULT NULL COMMENT '更新人',
  `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删 1-已删',
  `remark`      varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表';

-- ------------------------------------------------------------
-- 2. 角色
-- ------------------------------------------------------------
CREATE TABLE `sys_role` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name`        varchar(50) NOT NULL COMMENT '角色名',
  `code`        varchar(50) NOT NULL COMMENT '角色编码',
  `status`      tinyint     NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
  `create_by`   varchar(64)  DEFAULT NULL COMMENT '创建人',
  `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`   varchar(64)  DEFAULT NULL COMMENT '更新人',
  `update_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删 1-已删',
  `remark`      varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';

-- ------------------------------------------------------------
-- 3. 权限
-- ------------------------------------------------------------
CREATE TABLE `sys_permission` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code`        varchar(100) NOT NULL COMMENT '权限码（如 ticket:claim）',
  `name`        varchar(50) NOT NULL COMMENT '权限名',
  `type`        varchar(10) NOT NULL DEFAULT 'BUTTON' COMMENT '类型：MENU-菜单 BUTTON-按钮',
  `parent_id`   bigint unsigned NOT NULL DEFAULT 0 COMMENT '父级ID，0为根',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限表';

-- ------------------------------------------------------------
-- 4. 用户-角色关联
-- ------------------------------------------------------------
CREATE TABLE `sys_user_role` (
  `id`      bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  `role_id` bigint unsigned NOT NULL COMMENT '角色ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

-- ------------------------------------------------------------
-- 5. 角色-权限关联
-- ------------------------------------------------------------
CREATE TABLE `sys_role_permission` (
  `id`            bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_id`       bigint unsigned NOT NULL COMMENT '角色ID',
  `permission_id` bigint unsigned NOT NULL COMMENT '权限ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';

-- ------------------------------------------------------------
-- 6. 接入渠道
-- ------------------------------------------------------------
CREATE TABLE `channel` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `code`        varchar(30) NOT NULL COMMENT '渠道编码：WEB_API-网页接口 EMAIL-邮件',
  `name`        varchar(50) NOT NULL COMMENT '渠道名',
  `config_json` json        DEFAULT NULL COMMENT '渠道配置（如邮件IMAP参数）',
  `status`      tinyint     NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
  `create_by`   varchar(64)  DEFAULT NULL COMMENT '创建人',
  `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`   varchar(64)  DEFAULT NULL COMMENT '更新人',
  `update_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删 1-已删',
  `remark`      varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='接入渠道表';

-- ------------------------------------------------------------
-- 7. 渠道原始消息（幂等去重）
-- ------------------------------------------------------------
CREATE TABLE `channel_message` (
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `channel_id`      bigint unsigned NOT NULL COMMENT '渠道ID',
  `message_no`      varchar(64) NOT NULL COMMENT '渠道消息号（幂等键）',
  `ticket_id`       bigint unsigned DEFAULT NULL COMMENT '关联工单ID（回复时回填）',
  `direction`       tinyint NOT NULL DEFAULT 1 COMMENT '方向：1-客户进线 2-坐席回复',
  `customer_name`   varchar(50)  DEFAULT NULL COMMENT '客户名',
  `customer_contact` varchar(100) DEFAULT NULL COMMENT '客户联系方式',
  `title`           varchar(200) DEFAULT NULL COMMENT '标题',
  `content`         text         COMMENT '内容',
  `raw_json`        json         DEFAULT NULL COMMENT '原始报文',
  `create_time`     datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_no` (`message_no`),
  KEY `idx_channel_id` (`channel_id`),
  KEY `idx_ticket_id` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='渠道原始消息表';

-- ------------------------------------------------------------
-- 8. 工单主表（核心）
-- ------------------------------------------------------------
CREATE TABLE `ticket` (
  `id`                    bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ticket_no`             varchar(32) NOT NULL COMMENT '工单编号：T+yyyyMMdd+6位序列',
  `channel_id`            bigint unsigned NOT NULL COMMENT '来源渠道ID',
  `channel_message_id`    bigint unsigned DEFAULT NULL COMMENT '关联渠道消息ID',
  `title`                 varchar(200) NOT NULL COMMENT '工单标题',
  `description`           text COMMENT '工单描述',
  `category`              varchar(50) DEFAULT NULL COMMENT '最终分类（坐席确认后写入）',
  `priority`              tinyint NOT NULL DEFAULT 3 COMMENT '优先级：1-紧急 2-高 3-中 4-低',
  `status`                tinyint NOT NULL DEFAULT 2 COMMENT '状态：见 §6.1（2-待分派）',
  `group_id`              bigint unsigned DEFAULT NULL COMMENT '技能组ID',
  `agent_id`              bigint unsigned DEFAULT NULL COMMENT '当前处理坐席ID',
  `assign_strategy`       varchar(30) DEFAULT NULL COMMENT '实际采用的分派策略编码',
  `customer_name`         varchar(50)  DEFAULT NULL COMMENT '客户名',
  `customer_contact`      varchar(100) DEFAULT NULL COMMENT '客户联系方式',
  `sla_policy_id`         bigint unsigned DEFAULT NULL COMMENT 'SLA策略ID',
  `first_response_deadline` datetime DEFAULT NULL COMMENT '首次响应截止时间',
  `resolve_deadline`     datetime DEFAULT NULL COMMENT '解决截止时间',
  `first_responded_at`   datetime DEFAULT NULL COMMENT '首次响应时间',
  `resolved_at`          datetime DEFAULT NULL COMMENT '解决时间',
  `closed_at`            datetime DEFAULT NULL COMMENT '关闭时间',
  `ai_category`          varchar(50) DEFAULT NULL COMMENT 'AI分类建议',
  `ai_priority`          tinyint DEFAULT NULL COMMENT 'AI优先级建议',
  `ai_score`             decimal(5,2) DEFAULT NULL COMMENT 'AI分类置信度(0-1)',
  `version`              int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `create_by`            varchar(64)  DEFAULT NULL COMMENT '创建人',
  `create_time`          datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`            varchar(64)  DEFAULT NULL COMMENT '更新人',
  `update_time`          datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`              tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删 1-已删',
  `remark`               varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_no` (`ticket_no`),
  KEY `idx_status_priority_create` (`status`, `priority`, `create_time`),
  KEY `idx_agent_id` (`agent_id`),
  KEY `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单主表';

-- ------------------------------------------------------------
-- 9. 工单状态流转日志（事件流，全量记录）
-- ------------------------------------------------------------
CREATE TABLE `ticket_status_log` (
  `id`            bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ticket_id`     bigint unsigned NOT NULL COMMENT '工单ID',
  `from_status`   tinyint NOT NULL COMMENT '原状态',
  `to_status`     tinyint NOT NULL COMMENT '新状态',
  `event`         varchar(30) NOT NULL COMMENT '触发事件编码',
  `operator_id`   bigint unsigned DEFAULT NULL COMMENT '操作人ID（系统事件为NULL）',
  `operator_type` varchar(10) NOT NULL DEFAULT 'USER' COMMENT '操作类型：USER-用户 SYSTEM-系统',
  `remark`        varchar(500) DEFAULT NULL COMMENT '备注',
  `create_time`   datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_ticket_id` (`ticket_id`),
  KEY `idx_ticket_create` (`ticket_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单状态流转日志表';

-- ------------------------------------------------------------
-- 10. 工单评论/回复
-- ------------------------------------------------------------
CREATE TABLE `ticket_comment` (
  `id`         bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ticket_id`  bigint unsigned NOT NULL COMMENT '工单ID',
  `agent_id`   bigint unsigned DEFAULT NULL COMMENT '坐席ID',
  `type`       varchar(10) NOT NULL DEFAULT 'REPLY' COMMENT '类型：REPLY-回复客户 INTERNAL-内部备注',
  `content`    text NOT NULL COMMENT '内容',
  `visibility` varchar(10) NOT NULL DEFAULT 'ALL' COMMENT '可见性：ALL-客户可见 INTERNAL-仅内部',
  `create_by`  varchar(64)  DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`  varchar(64)  DEFAULT NULL COMMENT '更新人',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`    tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删 1-已删',
  PRIMARY KEY (`id`),
  KEY `idx_ticket_id` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单评论表';

-- ------------------------------------------------------------
-- 11. SLA 策略
-- ------------------------------------------------------------
CREATE TABLE `sla_policy` (
  `id`                   bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name`                 varchar(50) NOT NULL COMMENT '策略名',
  `priority`             tinyint NOT NULL COMMENT '适用优先级：1-紧急 2-高 3-中 4-低（唯一）',
  `first_response_minutes` int NOT NULL DEFAULT 120 COMMENT '首次响应时限（分钟）',
  `resolve_minutes`      int NOT NULL DEFAULT 1440 COMMENT '解决时限（分钟）',
  `auto_escalate`        tinyint NOT NULL DEFAULT 1 COMMENT '超时是否自动升级：0-否 1-是',
  `escalate_action`      json DEFAULT NULL COMMENT '升级动作：{"notifyGroupId":1} 或 {"reassignStrategy":"ROUND_ROBIN"}',
  `status`               tinyint NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
  `create_by`            varchar(64)  DEFAULT NULL COMMENT '创建人',
  `create_time`          datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`            varchar(64)  DEFAULT NULL COMMENT '更新人',
  `update_time`          datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`              tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删 1-已删',
  `remark`               varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_priority` (`priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SLA策略表';

-- ------------------------------------------------------------
-- 12. 工单 SLA 计时实例
-- ------------------------------------------------------------
CREATE TABLE `ticket_sla` (
  `id`                     bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ticket_id`              bigint unsigned NOT NULL COMMENT '工单ID',
  `sla_policy_id`          bigint unsigned NOT NULL COMMENT 'SLA策略ID',
  `first_response_deadline` datetime NOT NULL COMMENT '首次响应截止时间',
  `resolve_deadline`       datetime NOT NULL COMMENT '解决截止时间',
  `first_responded_at`     datetime DEFAULT NULL COMMENT '实际首次响应时间',
  `resolved_at`            datetime DEFAULT NULL COMMENT '实际解决时间',
  `first_response_status`  tinyint NOT NULL DEFAULT 0 COMMENT '响应状态：0-未到期 1-按时 2-超时',
  `resolve_status`         tinyint NOT NULL DEFAULT 0 COMMENT '解决状态：0-未到期 1-按时 2-超时',
  `escalation_triggered`   tinyint NOT NULL DEFAULT 0 COMMENT '是否已触发升级：0-否 1-是',
  `escalated_at`           datetime DEFAULT NULL COMMENT '升级时间',
  `create_time`            datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`            datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_id` (`ticket_id`),
  KEY `idx_deadline_status` (`first_response_deadline`, `escalation_triggered`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单SLA计时表';

-- ------------------------------------------------------------
-- 13. 分派策略配置
-- ------------------------------------------------------------
CREATE TABLE `dispatch_strategy` (
  `id`            bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `strategy_type` varchar(30) NOT NULL COMMENT '策略编码：ROUND_ROBIN-轮询 LEAST_LOADED-负载最低 SKILL_MATCH-技能匹配 AI_RECOMMEND-AI推荐',
  `weight`        int NOT NULL DEFAULT 100 COMMENT '权重：启用策略按权重降序尝试',
  `enabled`       tinyint NOT NULL DEFAULT 1 COMMENT '启用：0-否 1-是',
  `param_json`    json DEFAULT NULL COMMENT '策略参数',
  `create_time`   datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_strategy_type` (`strategy_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分派策略配置表';

-- ------------------------------------------------------------
-- 14. 坐席
-- ------------------------------------------------------------
CREATE TABLE `agent` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id`     bigint unsigned NOT NULL COMMENT '关联系统用户ID',
  `name`        varchar(50) NOT NULL COMMENT '坐席名',
  `status`      tinyint NOT NULL DEFAULT 1 COMMENT '在线状态：0-离线 1-在线',
  `current_load` int NOT NULL DEFAULT 0 COMMENT '当前负载（处理中+等待客户工单数）',
  `skill_tags`  varchar(500) DEFAULT NULL COMMENT '技能标签，JSON数组，如 ["售后","投诉"]',
  `create_by`   varchar(64)  DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`   varchar(64)  DEFAULT NULL COMMENT '更新人',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删 1-已删',
  `remark`      varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='坐席表';

-- ------------------------------------------------------------
-- 15. 技能组
-- ------------------------------------------------------------
CREATE TABLE `skill_group` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name`        varchar(50) NOT NULL COMMENT '技能组名',
  `description` varchar(200) DEFAULT NULL COMMENT '描述',
  `status`      tinyint NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
  `create_by`   varchar(64)  DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`   varchar(64)  DEFAULT NULL COMMENT '更新人',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删 1-已删',
  `remark`      varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能组表';

-- ------------------------------------------------------------
-- 16. 技能组-坐席关联
-- ------------------------------------------------------------
CREATE TABLE `skill_group_agent` (
  `id`       bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `group_id` bigint unsigned NOT NULL COMMENT '技能组ID',
  `agent_id` bigint unsigned NOT NULL COMMENT '坐席ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_agent` (`group_id`, `agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能组坐席关联表';

-- ------------------------------------------------------------
-- 17. 知识库文章
-- ------------------------------------------------------------
CREATE TABLE `knowledge_base` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `title`       varchar(200) NOT NULL COMMENT '标题',
  `category`    varchar(50) DEFAULT NULL COMMENT '分类',
  `content`     mediumtext NOT NULL COMMENT '正文',
  `status`      tinyint NOT NULL DEFAULT 1 COMMENT '状态：0-下架 1-上架',
  `author_id`   bigint unsigned DEFAULT NULL COMMENT '作者用户ID',
  `view_count`  int NOT NULL DEFAULT 0 COMMENT '浏览数',
  `create_by`   varchar(64)  DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`   varchar(64)  DEFAULT NULL COMMENT '更新人',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删 1-已删',
  `remark`      varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文章表';

-- ------------------------------------------------------------
-- 18. 知识库分段（向量存 ES，此处存元数据）
-- ------------------------------------------------------------
CREATE TABLE `kb_segment` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `kb_id`       bigint unsigned NOT NULL COMMENT '文章ID',
  `seq`         int NOT NULL DEFAULT 0 COMMENT '段序号',
  `content`     text NOT NULL COMMENT '段内容',
  `char_count`  int NOT NULL DEFAULT 0 COMMENT '字符数',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_kb_id` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库分段表';

-- ------------------------------------------------------------
-- 19. 审计日志
-- ------------------------------------------------------------
CREATE TABLE `audit_log` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id`     bigint unsigned DEFAULT NULL COMMENT '操作人ID',
  `username`    varchar(50) DEFAULT NULL COMMENT '操作人登录名',
  `action`      varchar(50) NOT NULL COMMENT '动作（如 TICKET_ASSIGN）',
  `target_type` varchar(30) DEFAULT NULL COMMENT '对象类型',
  `target_id`   bigint unsigned DEFAULT NULL COMMENT '对象ID',
  `detail_json` json DEFAULT NULL COMMENT '详情',
  `ip`          varchar(45) DEFAULT NULL COMMENT '操作IP',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表';

-- ------------------------------------------------------------
-- 20. LLM 调用记录（成本/质量可观测）
-- ------------------------------------------------------------
CREATE TABLE `ai_usage_log` (
  `id`               bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ticket_id`        bigint unsigned DEFAULT NULL COMMENT '工单ID',
  `scene`            varchar(20) NOT NULL COMMENT '场景：CLASSIFY-分类 SUGGEST-回复建议 DISPATCH-分派 EMBED-向量化',
  `model`            varchar(50) DEFAULT NULL COMMENT '模型名',
  `prompt_tokens`    int DEFAULT NULL COMMENT '输入token数',
  `completion_tokens` int DEFAULT NULL COMMENT '输出token数',
  `total_tokens`     int DEFAULT NULL COMMENT '总token数',
  `latency_ms`       int DEFAULT NULL COMMENT '耗时(ms)',
  `success`          tinyint NOT NULL DEFAULT 1 COMMENT '是否成功：0-否 1-是',
  `error_msg`        varchar(500) DEFAULT NULL COMMENT '错误信息',
  `create_time`      datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_ticket_id` (`ticket_id`),
  KEY `idx_scene_create` (`scene`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LLM调用记录表';

-- ============================================================
-- 初始数据（M1 里程碑执行）
-- ============================================================

-- 角色：ADMIN 管理员 / AGENT 坐席
INSERT INTO `sys_role` (`name`, `code`, `status`) VALUES
('管理员', 'ADMIN', 1),
('坐席', 'AGENT', 1);

-- 权限
INSERT INTO `sys_permission` (`code`, `name`, `type`) VALUES
('ticket:view', '查看工单', 'BUTTON'),
('ticket:claim', '领取工单', 'BUTTON'),
('ticket:assign', '分派工单', 'BUTTON'),
('ticket:reply', '回复工单', 'BUTTON'),
('ticket:resolve', '解决工单', 'BUTTON'),
('ticket:close', '关闭工单', 'BUTTON'),
('ticket:escalate', '升级工单', 'BUTTON'),
('sla:manage', '管理SLA策略', 'BUTTON'),
('kb:manage', '管理知识库', 'BUTTON'),
('agent:manage', '管理坐席', 'BUTTON'),
('dashboard:view', '查看看板', 'BUTTON');

-- 默认管理员账号：admin / Admin@12345（BCrypt 由 M1 启动时初始化，避免明文入库）
-- 说明：M1 提供 CommandLineRunner 检测 sys_user 为空时创建 admin 用户并绑定 ADMIN 角色。

-- 默认分派策略（M4 使用）
INSERT INTO `dispatch_strategy` (`strategy_type`, `weight`, `enabled`, `param_json`) VALUES
('ROUND_ROBIN',   100, 1, NULL),
('LEAST_LOADED',   80, 1, NULL),
('SKILL_MATCH',    90, 1, NULL),
('AI_RECOMMEND',   60, 0, NULL);   -- M6 接入 LLM 后再启用

-- 默认 SLA 策略（M3 使用）
INSERT INTO `sla_policy` (`name`, `priority`, `first_response_minutes`, `resolve_minutes`, `auto_escalate`, `escalate_action`) VALUES
('紧急', 1, 15, 240, 1, '{"notifyGroupId":1}'),
('高',   2, 60, 480, 1, '{"notifyGroupId":1}'),
('中',   3, 120, 1440, 1, '{"notifyGroupId":1}'),
('低',   4, 480, 2880, 0, NULL);

-- 默认渠道
INSERT INTO `channel` (`code`, `name`, `status`) VALUES
('WEB_API', '网页接口', 1),
('EMAIL', '邮件', 0);

-- 默认技能组与演示坐席（M2 里程碑执行，账号 admin/agent01，密码均为 Admin@12345，启动时初始化）
INSERT INTO `skill_group` (`name`, `description`, `status`) VALUES
('售后组', '售后问题处理', 1),
('投诉组', '投诉处理', 1);
