# TicketAI 企业智能客服工单系统 — AI 开发文档（v1.0）

> 本文档是唯一权威开发依据。本项目由 AI 编码工具（Cursor/Claude Code 等）全程开发，人类只做审查与验收。
> **AI 必须严格遵守本文档全部约束。文档未定义的内容，一律在代码中加 `// TODO(dev-doc): 待确认` 注释并停止，禁止自行发明。**

---

## §0 文档使用规则（AI 必读）

1. 按里程碑 §8 的顺序开发，**每个里程碑完成后**才能开始下一个；完成标志 = 该里程碑验收清单全部通过。
2. 所有表结构以 `sql/schema.sql` 为准，**禁止改动任何字段名、类型、注释**。如需变更，先问人类。
3. 依赖清单以 §2 为准，**禁止添加文档外的依赖**。
4. 状态机矩阵以 §5.1.3 为准，**禁止自行增加状态或事件**。
5. API 契约以 §6 为准，路径、方法、请求/响应结构不得改动。
6. 每个里程碑结束输出变更摘要：改了什么文件、跑了什么测试、验收项逐条打勾。
7. 编码风格遵守 §9；提交规范遵守 §9.8。
8. 遇到歧义：先查本文档，查不到 → 停止并询问人类，不要猜。

---

## §1 项目概述

### 1.1 定位

企业级智能客服工单系统：多渠道接入客户请求，生成工单并管理其全生命周期（创建 → 分派 → 处理 → 解决 → 关闭），通过 SLA 引擎保障响应时限，通过 LLM 提供自动分类、知识库问答与回复建议。

### 1.2 核心业务流程

```
客户消息(邮件/Web API) → 创建工单(NEW) → AI分类/优先级建议 → 自动分派(PENDING_ASSIGN)
→ 坐席领取(PROCESSING) → 回复(WAITING_CUSTOMER) → 解决(RESOLVED) → 关闭(CLOSED)
   └─ SLA超时 → 升级(ESCALATED) → 重新分派
```

### 1.3 设计红线（AI 不得违反）

1. **AI 只做增强，不做核心流转**。工单状态流转、SLA 计时、分派、权限全部由确定性代码实现；LLM 只输出"建议值"（分类建议、优先级建议、回复草稿、分派建议），系统可独立于 LLM 正常运行。
2. **自研状态机**，禁止使用 Activiti / Flowable / Spring StateMachine。
3. **自研 LLM 客户端**（HttpClient 封装），禁止引入 Spring AI。
4. 单体应用（单模块），禁止微服务化。

### 1.4 项目坐标

| 项 | 值 |
|---|---|
| 项目名 | ticket-ai |
| groupId | com.ticketai |
| artifactId | ticket-server |
| 语言 | Java 23（本机 JDK 23.0.1；pom 中 `maven.compiler.release=21` 编译目标，运行时 JDK 23） |
| 数据库 | MySQL 8.0，库名 `ticket_ai` |
| 前端 | Vue 3 + Vite（目录 `frontend/`） |

---

## §2 技术栈与依赖（锁定版本）

### 2.1 后端

| 依赖 | 版本 | 用途 |
|---|---|---|
| Spring Boot | 3.5.x（M1 时解析最新稳定版） | 框架 |
| JDK | 23（本机 JDK 23.0.1；`maven.compiler.release=21`） | 运行环境 |
| mybatis-plus-spring-boot3-starter | 3.5.9+ | ORM |
| mysql-connector-j | 8.4.x | 驱动 |
| spring-boot-starter-data-redis | 随 Boot | 缓存/锁 |
| redisson-spring-boot-starter | 3.40.x | 分布式锁 |
| rocketmq-spring-boot-starter | 2.3.2 | MQ（RocketMQ 5.x） |
| elasticsearch-java | 8.14.x + JDK HttpClient | ES 客户端（不用 spring-data-elasticsearch） |
| jjwt-api / jjwt-impl / jjwt-jackson | 0.12.6 | JWT |
| spring-boot-starter-security | 随 Boot | 认证授权 |
| knife4j-openapi3-jakarta-spring-boot-starter | 4.5.0 | API 文档 |
| lombok | 随 Boot | 简化代码 |
| mapstruct / mapstruct-processor | 1.6.x | 对象转换 |
| hutool-all | 5.8.x | 工具类 |
| spring-boot-starter-validation | 随 Boot | 参数校验 |
| spring-boot-starter-test | 随 Boot | 测试 |

### 2.2 前端

Vue 3.5.x + Vite 5.x + Element Plus 2.8.x + Pinia 2.x + vue-router 4.x + axios 1.x。

### 2.3 中间件（本地开发）

| 组件 | 版本 | 说明 |
|---|---|---|
| MySQL | 8.0.42 | 本机已有（Windows 服务 `MySQL`，root/1234，仅本机开发） |
| Redis | 3.0.504 | 本机已有（Windows 服务 `Redis`，127.0.0.1:6379）。老版本但本项目用到的特性（锁/INCR/字符串）全部兼容，无需升级 |
| RocketMQ | 5.3.x | Docker 容器（`infra/docker-compose.yml`），nameserver 127.0.0.1:9876 / broker 10911 |
| Elasticsearch | 8.14.x | Docker 容器（`infra/docker-compose.yml`），127.0.0.1:9200，`xpack.security.enabled: false` |

### 2.4 全局配置约定（application.yml 必含）

```yaml
spring:
  application.name: ticket-ai
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/ticket_ai?useUnicode=true&characterEncoding=utf8mb4&serverTimezone=Asia/Shanghai
    username: root
    password: 1234
  data:
    redis:
      host: 127.0.0.1
      port: 6379
  mvc:
    throw-exception-if-no-handler-found: true

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0

rocketmq:
  name-server: 127.0.0.1:9876
  producer:
    group: ticket-sla-producer

app:
  llm:
    base-url: ${LLM_BASE_URL:http://127.0.0.1:8000/v1}   # OpenAI 兼容
    api-key: ${LLM_API_KEY:}
    chat-model: ${LLM_CHAT_MODEL:gpt-4o-mini}
    embed-model: ${LLM_EMBED_MODEL:text-embedding-3-small}
    embed-dims: 1536
    timeout-ms: 15000
  es:
    uris: http://127.0.0.1:9200
  jwt:
    access-expire-minutes: 30
    refresh-expire-days: 7
  sla:
    compensation-cron: "0 */5 * * * ?"   # 补偿扫描：每5分钟
```

LLM 密钥通过环境变量注入 `.env`（不提交 git），代码中只读 `app.llm.api-key`。**禁止在代码或配置中硬编码密钥。**

---

## §3 系统架构

### 3.1 分层架构（五层）

```
controller  →  HTTP 处理、参数校验、DTO/VO 转换（薄）
service     →  业务规则、事务边界、编排
mapper      →  数据访问接口（MyBatis-Plus BaseMapper）
entity      →  表实体映射（DO）
database    →  MySQL / Redis / ES / RocketMQ（基础设施层）
```

约束（违反即返工）：
- Controller 不得调用 Mapper / Service 之外的层；不得写业务逻辑。
- Service 不得出现 HttpServletRequest、返回 DO（返回 VO 或领域对象）。
- Mapper 接口只允许 `@Mapper` + `extends BaseMapperX<T>` 风格，复杂 SQL 用 XML。
- 依赖方向单向：controller → service → mapper → entity。禁止反向依赖。

### 3.2 模块目录结构（后端，必须一致）

```
ticket-server/
├── pom.xml
├── src/main/java/com/ticketai/
│   ├── TicketServerApplication.java
│   ├── common/
│   │   ├── Result.java               # 统一返回
│   │   ├── PageResult.java
│   │   ├── exception/
│   │   │   ├── BusinessException.java
│   │   │   ├── ErrorCode.java        # 错误码枚举
│   │   │   └── GlobalExceptionHandler.java
│   │   ├── constant/                 # 常量
│   │   └── util/                     # 通用工具（编号生成等）
│   ├── config/
│   │   ├── MybatisPlusConfig.java    # 分页插件、MetaObjectHandler 自动填充
│   │   ├── RedissonConfig.java
│   │   ├── RocketMqConfig.java
│   │   ├── EsClientConfig.java
│   │   ├── SecurityConfig.java
│   │   ├── Knife4jConfig.java
│   │   ├── AsyncConfig.java          # 线程池（LLM 调用异步化）
│   │   └── WebMvcConfig.java         # traceId 过滤器注册、跨域
│   ├── security/
│   │   ├── JwtTokenProvider.java     # 生成/校验 access+refresh
│   │   ├── JwtAuthenticationFilter.java
│   │   ├── LoginUser.java            # 当前登录用户上下文
│   │   └── UserContextHolder.java
│   ├── controller/
│   │   ├── AuthController.java
│   │   ├── TicketController.java
│   │   ├── AgentController.java
│   │   ├── SkillGroupController.java
│   │   ├── SlaPolicyController.java
│   │   ├── ChannelController.java
│   │   ├── KnowledgeController.java
│   │   ├── DashboardController.java
│   │   └── AiController.java         # AI 建议类接口
│   ├── service/                      # 接口
│   │   ├── TicketService.java
│   │   ├── AgentService.java
│   │   ├── SlaService.java
│   │   ├── DispatchService.java
│   │   ├── KnowledgeService.java
│   │   ├── SearchService.java
│   │   ├── AiService.java
│   │   ├── AuditService.java
│   │   └── ChannelService.java
│   ├── service/impl/                 # 实现
│   ├── service/dispatch/             # 分派策略（§6.3）
│   │   ├── DispatchStrategy.java
│   │   ├── DispatchStrategyFactory.java
│   │   ├── RoundRobinStrategy.java
│   │   ├── LeastLoadedStrategy.java
│   │   ├── SkillMatchStrategy.java
│   │   └── AiRecommendStrategy.java
│   ├── state/                        # 状态机（§5.1）
│   │   ├── TicketStatus.java         # 状态枚举
│   │   ├── TicketEvent.java          # 事件枚举
│   │   ├── Transition.java           # 转移定义（含角色约束）
│   │   ├── StateMachine.java         # 引擎核心
│   │   └── StateMachineRegistry.java # 转移矩阵注册表
│   ├── mq/
│   │   ├── SlaDelayProducer.java     # SLA 延迟消息生产者
│   │   ├── SlaDelayConsumer.java     # 消费者（触发超时检查/升级）
│   │   └── SlaMessage.java
│   ├── ai/
│   │   ├── LlmClient.java            # 自研 OpenAI 兼容客户端（§6.5）
│   │   ├── LlmChatResult.java
│   │   ├── TicketClassifier.java     # 自动分类/优先级
│   │   ├── ReplySuggester.java       # RAG 回复建议
│   │   └── AiDispatcher.java         # AI 分派建议
│   ├── es/
│   │   ├── EsIndexInitializer.java   # 启动时建索引
│   │   ├── KnowledgeIndexService.java# 知识库文档同步
│   │   └── SearchService.java        # 全文+向量查询
│   ├── task/
│   │   ├── SlaCompensationTask.java  # 补偿扫描（§6.2.3）
│   │   └── EsSyncCompensationTask.java # ES 双写对账
│   ├── mapper/                       # 与表一一对应
│   ├── entity/                       # DO
│   ├── dto/                          # 入参
│   ├── vo/                           # 出参
│   ├── query/                        # 分页查询条件
│   └── convert/                      # MapStruct
└── src/main/resources/
    ├── application.yml
    ├── application-dev.yml
    ├── mapper/                       # XML
    └── db/
        └── schema.sql                # 与 sql/schema.sql 同步副本
└── sql/schema.sql                    # 唯一权威 DDL（见 §4）
```

前端 `frontend/` 目录见 §7。

## §4 数据库设计

唯一权威 DDL 在 `sql/schema.sql`（库 `ticket_ai`，20 张表）。本节为设计要点与约束。

### 4.1 表清单

| 表 | 用途 | 备注 |
|---|---|---|
| sys_user / sys_role / sys_permission / sys_user_role / sys_role_permission | RBAC 五表 | 启动时初始化 admin/agent01 账号与角色 |
| channel / channel_message | 渠道与原始消息 | channel_message.message_no 唯一，幂等去重 |
| ticket | 工单主表 | `version` 乐观锁；`status` 状态机驱动 |
| ticket_status_log | 状态流转日志 | 全量事件流，禁止删除 |
| ticket_comment | 评论/回复 | type: REPLY/INTERNAL |
| sla_policy / ticket_sla | SLA 策略与实例 | 每工单一份 ticket_sla |
| dispatch_strategy | 分派策略配置 | 权重降序尝试 |
| agent / skill_group / skill_group_agent | 坐席与技能组 | agent.current_load 冗余计数 |
| knowledge_base / kb_segment | 知识库与分段 | 向量存 ES，id 对应 |
| audit_log | 审计日志 | 敏感操作必须落审计 |
| ai_usage_log | LLM 调用记录 | 每次 LLM 调用必写 |

### 4.2 关键设计约束

1. **乐观锁**：所有对 `ticket` 的状态变更 SQL 必须携带 `WHERE version = #{expectVersion}` 且 `SET version = version + 1`，影响行数为 0 时抛 `BusinessException(CONCURRENT_MODIFY)`。MyBatis-Plus 用 `@Version` 注解自动处理。
2. **状态变更唯一入口**：任何代码不得直接 `UPDATE ticket SET status=...`，必须走 `TicketService#transition()`（内部调状态机 + 写 ticket_status_log + 更新 ticket_sla）。
3. **幂等创建**：渠道消息 `message_no` 唯一冲突时返回已存在工单，不重复创建。
4. **编号生成**：`ticket_no = "T" + yyyyMMdd + 6位序列`（当天自增，Redis INCR，key `ticket:no:{yyyyMMdd}`），冲突兜底重试 3 次。
5. **删除策略**：工单/坐席/知识库逻辑删除；ticket_status_log、audit_log、ai_usage_log、channel_message 物理保留，禁止删除。
6. **ES 数据一致性**：kb_segment 落库后同步写 ES 索引（§5.4），失败进重试队列（RocketMQ 普通消息），`EsSyncCompensationTask` 每 10 分钟对账补偿。
7. **幂等总则**：有外部副作用的操作必须幂等或可重入——渠道建单（message_no 唯一键）、SLA 延迟消息消费（ticket_sla 状态检查）、补偿扫描（escalation_triggered 条件）、ES 写入（upsert）、LLM 调用（SETNX 防重）、refresh 旋转（Lua 原子）。状态机事件 + 乐观锁天然拒绝重复事件，无需额外防重，但代码注释需说明。

---

## §5 核心模块设计

## §5.1 状态机引擎（自研，禁止引入第三方）

### 5.1.1 状态定义（ticket.status，tinyint）

| code | 枚举 | 含义 |
|---|---|---|
| 1 | NEW | 新建（草稿，未提交） |
| 2 | PENDING_ASSIGN | 待分派 |
| 3 | PROCESSING | 处理中 |
| 4 | WAITING_CUSTOMER | 等待客户 |
| 5 | RESOLVED | 已解决 |
| 6 | CLOSED | 已关闭 |
| 7 | ESCALATED | 已升级 |
| 8 | CANCELLED | 已取消 |

### 5.1.2 事件定义（TicketEvent）

| 事件 | 触发方 | 说明 |
|---|---|---|
| SUBMIT | 用户 | 提交草稿 |
| AUTO_ASSIGN | 系统 | 自动分派成功 |
| MANUAL_ASSIGN | 管理员 | 手动分派 |
| CLAIM | 坐席 | 抢单领取 |
| REPLY | 坐席 | 回复客户 |
| CUSTOMER_REPLY | 系统/坐席 | 客户来信 |
| RESOLVE | 坐席/管理员 | 标记解决 |
| CLOSE | 坐席/管理员 | 关闭 |
| REOPEN | 坐席/管理员 | 重开 |
| ESCALATE | 坐席/管理员 | 人工升级 |
| TIMEOUT_ESCALATE | 系统 | SLA 超时升级 |
| CANCEL | 创建人/管理员 | 取消 |

### 5.1.3 转移矩阵（权威，禁止增删）

| 当前状态 | 事件 | 目标状态 | 权限要求 |
|---|---|---|---|
| NEW | SUBMIT | PENDING_ASSIGN | ticket:view |
| NEW | CANCEL | CANCELLED | ticket:view |
| PENDING_ASSIGN | AUTO_ASSIGN | PROCESSING | SYSTEM |
| PENDING_ASSIGN | MANUAL_ASSIGN | PROCESSING | ticket:assign |
| PENDING_ASSIGN | CLAIM | PROCESSING | ticket:claim |
| PENDING_ASSIGN | ESCALATE | ESCALATED | ticket:escalate |
| PENDING_ASSIGN | CANCEL | CANCELLED | ticket:close |
| PROCESSING | REPLY | WAITING_CUSTOMER | ticket:reply |
| PROCESSING | RESOLVE | RESOLVED | ticket:resolve |
| PROCESSING | ESCALATE | ESCALATED | ticket:escalate |
| PROCESSING | TIMEOUT_ESCALATE | ESCALATED | SYSTEM |
| PROCESSING | CANCEL | CANCELLED | ticket:close |
| WAITING_CUSTOMER | CUSTOMER_REPLY | PROCESSING | SYSTEM |
| WAITING_CUSTOMER | REPLY | WAITING_CUSTOMER | ticket:reply |
| WAITING_CUSTOMER | RESOLVE | RESOLVED | ticket:resolve |
| WAITING_CUSTOMER | TIMEOUT_ESCALATE | ESCALATED | SYSTEM |
| RESOLVED | REOPEN | PROCESSING | ticket:resolve |
| RESOLVED | CLOSE | CLOSED | ticket:close |
| CLOSED | REOPEN | PROCESSING | ticket:assign |
| ESCALATED | MANUAL_ASSIGN | PROCESSING | ticket:assign |
| ESCALATED | RESOLVE | RESOLVED | ticket:resolve |
| ESCALATED | CANCEL | CANCELLED | ticket:close |
| CANCELLED | （无出口） | — | — |

### 5.1.4 引擎设计（state/ 包）

```java
// state/TicketStatus.java —— 枚举，含 code 与 desc
public enum TicketStatus {
    NEW(1, "新建"), PENDING_ASSIGN(2, "待分派"), PROCESSING(3, "处理中"),
    WAITING_CUSTOMER(4, "等待客户"), RESOLVED(5, "已解决"), CLOSED(6, "已关闭"),
    ESCALATED(7, "已升级"), CANCELLED(8, "已取消");
    private final int code; private final String desc;
}

// state/TicketEvent.java —— 枚举：SUBMIT, AUTO_ASSIGN, MANUAL_ASSIGN, CLAIM, REPLY,
// CUSTOMER_REPLY, RESOLVE, CLOSE, REOPEN, ESCALATE, TIMEOUT_ESCALATE, CANCEL

// state/Transition.java —— record(TicketStatus from, TicketEvent event,
//                              TicketStatus to, String requiredPermission)
//     requiredPermission 为 "SYSTEM" 表示仅系统可触发

// state/StateMachine.java —— 核心
//     boolean canTransition(from, event)
//     TicketStatus fire(from, event)   // 非法流转抛 IllegalTransitionException
//     Set<TicketEvent> allowedEvents(from)

// state/StateMachineRegistry.java —— 静态注册表，一次性初始化全部转移（禁止用数据库配置转移矩阵）
```

实现要求：
1. 转移矩阵用 `Map<TicketStatus, Map<TicketEvent, Transition>>`，`StateMachineRegistry` 静态块全量初始化，与 §5.1.3 完全一致。
2. `TicketService#transition(ticketId, event)` 流程：查工单 → `stateMachine.canTransition` 校验（不通过抛 `BusinessException(ILLEGAL_TRANSITION)`，message 带当前状态与事件）→ 权限校验（`requiredPermission` 非 SYSTEM 时检查当前用户）→ 乐观锁更新 ticket.status → 写 ticket_status_log → 触发事件后置动作（§5.1.5）。
3. 非法流转必须抛异常并记录日志，禁止静默忽略。

### 5.1.5 事件后置动作（在 transition 事务内/事务提交后执行）

| 事件 | 后置动作 |
|---|---|
| SUBMIT | 创建 ticket_sla（按优先级匹配策略），投递延迟消息 |
| AUTO_ASSIGN / MANUAL_ASSIGN | 写 agent_id、group_id；若已有 SLA 未启动计时 → 启动 |
| CLAIM | 写 agent_id、group_id=NULL、current_load+1 |
| REPLY | 若 first_responded_at 为空 → 置当前时间（首次响应计时停止） |
| CUSTOMER_REPLY | current_load 不变 |
| RESOLVE | 置 resolved_at；ticket_sla.resolved_at、resolve_status 结算 |
| CLOSE | 置 closed_at；坐席 current_load-1 |
| REOPEN | 清 resolved_at/closed_at；重建 ticket_sla（重新计时） |
| ESCALATE / TIMEOUT_ESCALATE | ticket_sla.escalation_triggered=1、escalated_at；按 sla_policy.escalate_action 执行（通知/重分派） |
| CANCEL | 置 closed_at |

## §5.2 SLA 引擎（延迟消息 + 补偿扫描，双保险）

### 5.2.1 流程

```
SUBMIT(创建工单)
  → 按 priority 查启用 sla_policy → 建 ticket_sla
  → 计算 first_response_deadline = now + first_response_minutes
  → 投递 RocketMQ 延迟消息（延迟时长 = first_response_minutes 分钟，消息体 = ticketId + 检查类型 FIRST_RESPONSE）
  → 同一机制对 resolve_deadline 投递检查消息（类型 RESOLVE）
消费者收到消息：
  → 查 ticket_sla：若 escalation_triggered=1 或对应指标已达成（已响应/已解决）→ 忽略（幂等）
  → 否则：更新 first_response_status=2（超时），触发状态机事件 TIMEOUT_ESCALATE
补偿扫描（SlaCompensationTask，每5分钟，cron 见 §2.4）：
  → 查 ticket_sla WHERE escalation_triggered=0 AND first_response_status=0
         AND first_response_deadline < NOW()  → 触发超时升级（消息丢失兜底）
  → resolve_deadline 同理
```

### 5.2.2 消息设计

- Topic：`ticket-sla-check`，Tag：`FIRST_RESPONSE` / `RESOLVE`。
- 消息体：`SlaMessage(ticketId, slaId, checkType, deadline)`，JSON 序列化。
- 延迟投递：RocketMQ 5.x 定时消息（`message.setDeliverTime(毫秒时间戳)`，支持任意延迟）；若所连版本不支持，降级为 18 级延迟级别中最接近的一级，并在注释中说明。
- 消费失败：RocketMQ 默认重试（16 次退避），重试耗尽仍失败 → 记录日志并由补偿扫描兜底。**禁止配置死信队列消费后静默丢弃**。

### 5.2.3 结算规则

| 时机 | 动作 |
|---|---|
| 首次 REPLY 时（first_responded_at 置位） | first_response_status=1（按时）；若已超时则保持 2 |
| RESOLVE 时（resolved_at 置位） | resolve_status=1（按时）或 2（超时） |
| 升级触发 | escalation_triggered=1、escalated_at=now；执行 sla_policy.escalate_action（默认：通知技能组负责人——M3 实现为写 audit_log + 控制台站内信占位） |

## §5.3 分派策略与并发控制

### 5.3.1 分派（service/dispatch/ 包）

```java
public interface DispatchStrategy {
    String type();                       // 策略编码
    Long dispatch(Ticket ticket);        // 返回 agentId，找不到返回 null
}
```

| 实现 | 逻辑 |
|---|---|
| RoundRobinStrategy | Redis INCR `dispatch:rr:{groupId}` % 在线坐席数 |
| LeastLoadedStrategy | 查在线坐席按 current_load ASC、取最小（同负载取 id 最小） |
| SkillMatchStrategy | 工单 category 映射技能标签 → 匹配坐席 skill_tags；无匹配返回 null |
| AiRecommendStrategy | 调 AiDispatcher 返回建议 agentId；LLM 不可用或置信度低返回 null |

- `DispatchStrategyFactory`：读 `dispatch_strategy` 表 enabled 策略按 weight 降序逐个执行，第一个返回非 null 的生效；全失败 → 工单保持 PENDING_ASSIGN（由 CLAIM 抢单兜底）。
- 分派时机：SUBMIT 后由 `DispatchService#dispatchAsync` 异步执行（线程池），成功 → `transition(AUTO_ASSIGN)`；`assign_strategy` 记录实际生效策略。
- 手动分派：`POST /api/v1/tickets/{id}/assign` 直接 `transition(MANUAL_ASSIGN)`。

### 5.3.2 抢单并发（CLAIM 双保险）

```java
// 1. Redis 分布式锁（Redisson，lease 10s，等待 3s）
RLock lock = redissonClient.getLock("ticket:claim:" + ticketId);
// 2. 锁内执行乐观锁更新（MyBatis-Plus @Version 自动拼接）
//    UPDATE ticket SET status=3, agent_id=?, version=version+1
//    WHERE id=? AND status=2 AND deleted=0
//    影响行数=0 → 抛 CONCURRENT_MODIFY
```

领取成功后：坐席 current_load+1，写 ticket_status_log（CLAIM）。

## §5.4 检索体系（ES 8.x，全文 + 向量）

### 5.4.1 索引定义（EsIndexInitializer 启动时创建）

```
索引1: kb_segment_index
  id (keyword)            = kb_segment.id
  kb_id (long), title (text), category (keyword), content (text)
  content_vector (dense_vector, dims=1536, similarity=cosine)

索引2: ticket_index        （仅 RESOLVED/CLOSED 的已解决工单，用于相似工单召回）
  id (keyword)            = ticket.id
  ticket_no (keyword), title (text), description (text), category (keyword)
  content_vector (dense_vector, dims=1536, similarity=cosine)
```

### 5.4.2 写入与一致性

1. 知识库上架时：文章分段（切分规则：按 Markdown 标题层级分段；无标题段落按 500 字定长滑动窗口切分，重叠 50 字）→ 写 kb_segment → embedding 化 → 写 ES `kb_segment_index`。
2. 工单进入 RESOLVED 时：异步写 ES `ticket_index`（含 embedding）。
3. 失败补偿：同步失败 → 发普通消息到 `es-sync-retry` 重试 3 次；仍失败 → 由 `EsSyncCompensationTask` 每 10 分钟对比 MySQL 与 ES 的 id 差集补写。

### 5.4.3 查询

- 知识库检索：`match(content|title, keyword)` + 可选 `knn(content_vector, queryVector, k=5)`，两者分数加权合并（权重 0.6/0.4）。
- 相似工单召回：`knn(ticket_index.content_vector, queryVector, k=3)`。
- embedding 用 `app.llm.embed-model`，dims 与 §2.4 配置一致（1536），LLM 不可用时降级为纯全文检索。

## §5.5 AI 模块（自研 LlmClient，禁止 Spring AI）

### 5.5.1 LlmClient（ai/ 包）

- 基于 JDK `java.net.http.HttpClient` + Jackson，对接 OpenAI 兼容协议（`base-url` 可配）。
- 方法：`ChatResult chat(List<Message> messages, int maxTokens)`、`ChatResult chatJson(List<Message> messages)`（强制 `response_format: {"type":"json_object"}`，解析并校验 JSON）、`float[] embed(String text)`。
- 超时 15s（可配），失败抛 `LlmException`（带原因分类：TIMEOUT / RATE_LIMIT / SERVER_ERROR / PARSE_ERROR）。
- 每次调用成功/失败都必须写 `ai_usage_log`（tokens、latency、success、error_msg）。
- **禁止把 api-key 写进日志、代码、配置仓库**；读取 `app.llm.api-key`。

### 5.5.2 三个 AI 场景（全部为"建议"，核心流程不依赖）

**场景1 自动分类 + 优先级建议（TicketClassifier）**
- 触发：工单创建后异步执行（线程池），结果写回 ticket.ai_category / ai_priority / ai_score。
- **幂等（防重复调 LLM）**：执行前 `SETNX ai:classify:{ticketId}`（TTL 10min），已存在则跳过；执行完毕写回后释放。禁止直接重复调用。
- Prompt（系统）：你是客服工单分类器，输出 JSON：`{"category": "售后|售前|投诉|咨询|其他", "priority": 1|2|3|4, "confidence": 0.0-1.0}`；输入：工单标题+描述。
- 坐席可"采纳"分类（写入 category 字段）或修改。

**场景2 RAG 回复建议（ReplySuggester）**
- 触发：坐席在工单详情页点"生成建议"（POST /api/v1/tickets/{id}/ai-suggest）。
- 流程：工单内容 embedding → ES 召回相似已解决工单 top3 + 知识库分段 top3 → 组装 prompt（参考上下文 + 客户问题）→ chatJson 生成 `{"reply": "...", "kbRefs": [id...]}` → 返回前端（坐席编辑后发送）。
- 限制：reply 长度 ≤ 500 字（prompt 约束 + 代码截断兜底）。

**场景3 AI 分派建议（AiDispatcher）**
- 输入：工单分类、标题、描述；输出 `{"agentId": 123|null, "reason": "..."}`。
- 仅在 `AI_RECOMMEND` 策略启用时被调用；LLM 不可用 → 返回 null（工厂自动降级其他策略）。

### 5.5.3 降级策略（红线：LLM 挂了系统必须照常跑）

| 场景 | 降级行为 |
|---|---|
| 分类失败 | ai_category=NULL，坐席手工分类；工单流程不受影响 |
| 回复建议失败 | 接口返回明确错误码 `AI_UNAVAILABLE`，前端提示 |
| 分派建议失败 | 返回 null，工厂用其他策略 |
| embedding 失败 | 检索降级为纯全文检索 |

## §5.6 认证与权限（JWT 双 token + RBAC）

1. 登录 `POST /api/v1/auth/login`：校验 BCrypt 密码 → 签发 accessToken（30min）+ refreshToken（7d，存 Redis `refresh:{userId}`，单设备滚动替换）。
2. 刷新 `POST /api/v1/auth/refresh`：校验 refreshToken 且与 Redis 一致 → 旋转签发新对；不一致 → 401。**校验与替换必须用 Redis Lua 脚本原子执行**（读值→比对→替换），防止并发刷新双成功。
3. `JwtAuthenticationFilter`：解析 `Authorization: Bearer` → 校验签名与过期 → 构建 `LoginUser(userId, username, agentId?, permissions)` 放入 `UserContextHolder`；无效 → 401。
4. 权限：`@PreAuthorize("hasAuthority('ticket:claim')")`；权限码来自数据库（启动时加载进内存缓存，角色变更时手动刷新或每 5 分钟刷新）。
5. 白名单（免认证）：`/api/v1/auth/login`、`/api/v1/auth/refresh`、`/api/v1/channels/web-api/tickets`（客户渠道创建工单）、Knife4j 文档路径、`/error`。
6. 操作审计：assign/close/escalate/cancel/sla 策略变更等敏感操作必须写 audit_log（AuditService，AOP 注解 `@Audited(action="TICKET_ASSIGN")` 实现）。

## §6 API 契约（统一前缀 /api/v1）

统一返回 `Result<T>`：`{"code":200,"message":"success","data":{...},"timestamp":...}`；分页 data 为 `{"records":[],"total":0,"page":1,"size":20,"pages":0}`。认证：`Authorization: Bearer {accessToken}`。

### 6.1 错误码（ErrorCode 枚举，禁止新增枚举值之外的自定义 code）

| code | 枚举 | 场景 |
|---|---|---|
| 400 | PARAM_ERROR | 参数校验失败 |
| 401 | UNAUTHORIZED | 未认证/token 无效 |
| 403 | FORBIDDEN | 无权限 |
| 404 | NOT_FOUND | 资源不存在 |
| 409 | CONCURRENT_MODIFY | 乐观锁冲突 |
| 409 | ILLEGAL_TRANSITION | 非法状态流转 |
| 409 | DUPLICATE_MESSAGE | 渠道消息重复 |
| 500 | SYSTEM_ERROR | 系统异常 |
| 503 | AI_UNAVAILABLE | LLM 不可用 |

### 6.2 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/auth/login | body: {username, password} → {accessToken, refreshToken} |
| POST | /api/v1/auth/refresh | body: {refreshToken} → 新 {accessToken, refreshToken} |

### 6.3 工单（权限见 §5.1.3）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/v1/tickets | 分页：page,size,status,priority,category,agentId,keyword,startTime,endTime,sort；按 createTime desc 默认 |
| GET | /api/v1/tickets/{id} | 详情（含 sla、agent、channel 冗余信息） |
| GET | /api/v1/tickets/{id}/timeline | 时间线：ticket_status_log ∪ ticket_comment 按时间合并 |
| POST | /api/v1/tickets | 创建（body: {title, description, category, priority, customerName, customerContact, channelId}）→ 返回 {id, ticketNo} |
| POST | /api/v1/tickets/{id}/claim | 抢单（ticket:claim） |
| POST | /api/v1/tickets/{id}/assign | 手动分派 body: {agentId}（ticket:assign） |
| POST | /api/v1/tickets/{id}/reply | 回复 body: {content, visibility=ALL|INTERNAL}（ticket:reply） |
| POST | /api/v1/tickets/{id}/resolve | 解决（ticket:resolve） |
| POST | /api/v1/tickets/{id}/close | 关闭（ticket:close） |
| POST | /api/v1/tickets/{id}/reopen | 重开（ticket:resolve） |
| POST | /api/v1/tickets/{id}/escalate | 人工升级（ticket:escalate） |
| POST | /api/v1/tickets/{id}/cancel | 取消（ticket:close） |
| POST | /api/v1/tickets/{id}/ai-suggest | 生成回复建议（ticket:reply）→ {reply, kbRefs[]} |
| POST | /api/v1/tickets/{id}/accept-category | 采纳 AI 分类 body: {category, priority}（ticket:view） |

### 6.4 渠道（公开）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/channels/web-api/tickets | 客户创建工单，body: {messageNo, customerName, customerContact, title, content}；messageNo 重复返回已建工单（幂等） |

### 6.5 坐席 / 技能组 / SLA / 知识库 / 看板（标准 CRUD，权限码见 schema.sql 初始化数据）

- `/api/v1/agents`：GET 列表（可按 groupId、status 过滤）、PUT /{id}/status {status}、PUT /{id} 更新技能标签
- `/api/v1/skill-groups`：GET/POST/PUT/DELETE；`/api/v1/skill-groups/{id}/agents`：PUT 批量设置组内坐席 {agentIds[]}
- `/api/v1/sla-policies`：GET/POST/PUT/DELETE（sla:manage）
- `/api/v1/kb`：GET 分页、GET /{id}、POST、PUT /{id}、DELETE /{id}（kb:manage，删除=逻辑删除+ES 删除）；POST /api/v1/kb/search：body {keyword, vector?} → 命中列表（含 score）
- `/api/v1/dashboard/stats`：{totalByStatus{}, slaOnTimeRate, avgFirstResponseMinutes, todayNew, todayResolved}（dashboard:view）

---

## §7 前端（frontend/，Vue 3 + Element Plus）

### 7.1 页面与路由

| 路由 | 页面 | 对应 API |
|---|---|---|
| /login | 登录 | auth/login |
| /tickets | 工单列表（筛选+分页） | tickets GET |
| /tickets/:id | 工单详情（时间线、评论、AI 建议抽屉、操作按钮按状态禁用） | tickets 系列 |
| /tickets/new | 创建工单 | tickets POST |
| /agents | 坐席管理 | agents |
| /skill-groups | 技能组管理 | skill-groups |
| /sla | SLA 策略管理 | sla-policies |
| /kb | 知识库列表/编辑 | kb |
| /dashboard | 统计看板（ECharts 或 Element 表格+进度条） | dashboard/stats |

### 7.2 规范

1. `src/api/` 按模块封装 axios；拦截器：注入 token、401 时调 refresh 后重放一次（失败跳登录）。
2. Pinia store：`useUserStore`（token、用户信息、权限码数组）；按钮级权限用 `v-permission` 指令（校验 store 权限码）。
3. 工单详情页操作按钮的可用性按 ticket.status 推导（与后端状态机矩阵一致，前端只做展示层禁用，后端强制校验）。
4. AI 建议抽屉：请求 ai-suggest，展示回复草稿（可编辑）+ 引用条目（kbRefs 对应标题），"发送"调用 reply。

---

## §8 里程碑计划（严格按序，DoD 全过才进下一阶段）

### M1 骨架 + 登录 + 工单 CRUD + 状态机（Week 1）

- 建库执行 schema.sql；Spring Boot 工程骨架；MyBatis-Plus/Redis/Redisson/knife4j 配置；统一返回与全局异常；admin 初始化（CommandLineRunner）
- JWT 双 token 登录 + SecurityConfig 白名单 + UserContextHolder
- ticket CRUD + ticket_status_log 写入 + 状态机全部代码 + 编号生成器
- **DoD**：启动无报错；登录接口通；创建工单状态为 2；`StateMachineTest` 覆盖矩阵全部 22 条转移 + 至少 10 条非法转移断言；`TicketServiceTest` 覆盖乐观锁冲突

### M2 渠道 + 坐席/技能组（Week 2）

- channel/channel_message 幂等创建工单（WEB_API 渠道公开接口）；邮件渠道仅实现接口与配置占位（不接真实 IMAP）
- agent/skill_group 管理；agent01 坐席初始化；技能标签 JSON 读写
- **DoD**：重复 messageNo 两次请求返回同一工单；agent01 可登录且权限为坐席；技能组接口 CRUD 测试通过

### M3 SLA 引擎（Week 3）

- sla_policy CRUD；创建工单自动建 ticket_sla + 投递延迟消息；消费者结算；补偿扫描；升级动作（audit_log + 站内信占位）
- **DoD**：用 first_response_minutes=1 的临时策略验证：未响应 → 1 分钟后升级（状态 7）；已响应 → 不升级；补偿任务单测（模拟消息丢失后仍升级）

### M4 分派 + 并发（Week 4）

- 4 个策略 + 工厂 + 异步自动分派；抢单 Redisson 锁 + 乐观锁；current_load 维护
- **DoD**：并发测试——10 线程同时抢同一张工单，恰好 1 个成功、9 个 CONCURRENT_MODIFY；AI_RECOMMEND 未启用时工厂正确跳过；全策略返回 null 时工单保持待分派

### M5 知识库 + ES 检索（Week 5）

- kb 管理；分段切分（按标题层级 + 500 字定长滑动窗口）；ES 索引创建与写入；检索接口；双写补偿
- **DoD**：ES 启动即建索引；检索接口 keyword 与向量两种模式可用；模拟 ES 写入失败 → MQ 重试 → 补偿对账补齐；embedding 不可用降级全文检索

### M6 AI 模块（Week 6）

- LlmClient + 三场景 + 降级 + ai_usage_log
- **DoD**：mock LLM 服务：分类写回 ticket.ai_category/ai_priority/ai_score；ai-suggest 返回草稿+引用；LLM 超时 → 分类为 null、接口返回 AI_UNAVAILABLE、系统流程不受影响；ai_usage_log 有记录

### M7 看板 + 审计 + 权限收尾（Week 7）

- dashboard/stats 四指标；@Audited AOP；权限码缓存与校验；前端全部页面（§7）
- **DoD**：无权限用户调接口返回 403；敏感操作均落 audit_log；看板指标与库内数据一致

### M8 收尾（Week 8）

- 补测试（目标：状态机/SLA/分派/并发 相关类行覆盖 ≥ 80%）；演示数据脚本 `sql/demo_data.sql`（约 100 张工单、20 篇文章、含超时/按时样本）；README.md + 架构图；全量冒烟（启动 → 登录 → 建单 → 分派 → 回复 → 解决 → 关闭 → 看板）
- **DoD**：`mvn clean test` 全绿；`mvn spring-boot:run` 一键启动；前端 `npm run build` 通过；演示数据可用

---

## §9 工程规范

1. **命名**：类 PascalCase、方法/变量 camelCase、常量 UPPER_SNAKE、包名小写；布尔字段禁止 is 前缀（Lombok 兼容）。
2. **依赖注入**：构造器注入（`@RequiredArgsConstructor`），禁止字段注入 `@Autowired`。
3. **异常**：业务异常一律 `throw new BusinessException(ErrorCode.XXX)`；Controller 不 catch；GlobalExceptionHandler 统一处理；`Exception` 兜底记 error 日志返回 500。
4. **时间**：一律 `LocalDateTime`，禁止 `java.util.Date`。
5. **日志**：logback；`TraceIdFilter`（OncePerRequestFilter）生成 traceId 入 MDC，业务日志记录 traceId；敏感操作 info、异常 warn/error；禁止打印完整对象中的敏感字段。
6. **事务**：写操作 Service 方法加 `@Transactional(rollbackFor = Exception.class)`；跨 MQ 的操作"先落库后发消息"（本地事务内投递）。
7. **测试**：JUnit5 + Mockito；命名 `XxxTest`；状态机、SLA 结算、分派、并发、幂等必须有测试；测试禁止连真实中间件（用 H2 或 mock）。
8. **Git**：Conventional Commits（feat/fix/test/docs/refactor/chore）；每里程碑打 tag `M1`~`M8`；`.gitignore` 排除 `.env`、`target/`、`node_modules/`、`application-dev.yml`（可含密钥）。

## §10 禁止事项（违反即返工）

1. 禁止添加 §2 之外的依赖。
2. 禁止修改 sql/schema.sql 的字段名/类型/注释；禁止新增表（需要先问人类）。
3. 禁止修改 §5.1.3 状态机矩阵。
4. 禁止绕过状态机直接 UPDATE ticket.status。
5. 禁止引入 Spring AI / Activiti / Flowable / Spring StateMachine / spring-data-elasticsearch。
6. 禁止硬编码 API key 或提交 `.env`。
7. 禁止删除 ticket_status_log / audit_log / ai_usage_log 数据。
8. 禁止在 Controller 写业务逻辑、在 Service 返回 DO、跨层调用 Mapper。
9. 禁止用 `@TableField(exist = false)` 隐藏业务字段后绕过规范。
10. 禁止复制粘贴代码时引入重复实现（如两个 place 的 ticket 状态判断）。

## §11 最终验收清单（M8 结束后逐项核对）

- [ ] `mvn clean test` 全绿，关键类覆盖率达标
- [ ] 全新环境：执行 schema.sql → 启动 → 初始化脚本 → 演示数据 → 全流程冒烟通过
- [ ] 状态机：手工通过 Knife4j 验证非法流转返回 ILLEGAL_TRANSITION
- [ ] SLA：演示数据中可见"按时/超时"两类样本，超时工单已升级
- [ ] 并发：M4 并发测试用例保留，可重复执行
- [ ] AI：配置真实 key 后分类/建议可用；不配 key 系统全流程不受影响
- [ ] 前端：8 个页面可访问，操作按钮与状态联动正确
- [ ] 文档：README 含架构图、启动步骤、演示账号（admin/agent01，Admin@12345）

---

*文档版本 v1.0，变更需人类确认后由 AI 更新本节末尾的变更日志。*
