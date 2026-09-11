# TicketAI 企业生产差距分析（P0/P1/P2 整改清单）

> 版本：v1.0　日期：2026-09-11
> 依据：2026-09 对 ticket-server / frontend / infra / sql 四个目录的只读代码走查 + git 历史核查 + 关键缺陷人工复核。
> 修复顺序总体遵循 **P0 → P1 → P2**，其中 P0-4（SLA 补偿毒化）与 P0-9（前端刷新死锁）为**代码级 bug，建议当天修复**，其余多为工程补强。

## 实施进度（2026-09-11 更新）

| 项 | 状态 | 说明 |
|---|---|---|
| P0-1 JWT secret | ✅ | `${JWT_SECRET:}` 环境变量注入 + 构造器长度校验（<32 拒绝启动） |
| P0-2 渠道鉴权 | ✅ | `Authorization: Bearer <appKey>` + Redis 固定窗口限流 600/分钟；4 个新单测（401×2/429/放行） |
| P0-3 演示账号 | ✅ | `app.init.seed-demo-accounts` 开关，prod 关闭；口令日志全部移除 |
| P0-4 SLA 补偿毒化 | ✅ | 逐条 try/catch 隔离 + 升级前 `canTransition` 预判 + RESOLVE/CLOSE/CANCEL 结算钩子；3 个新单测 |
| P0-5 刷新死锁 | ✅ | single-flight 顺序修正（首个请求自己重放），附带业务码校验 |
| P0-6 prod profile | ✅ | `application-prod.yml`（连接串 fail-fast、swagger 关闭、NoLoggingImpl） |
| P0-7 登出/吊销 | ✅ | `/api/v1/auth/logout`（permitAll）+ jti 黑名单 + refresh 存 SHA-256；type claim；3 个新单测 |
| P0-8 ES 双写删除 | ✅ | 更新前先删旧分段 ES 文档；知识库 ES 写入/删除移出长事务（afterCommit）；ticket_index 失败进 es-sync-retry；两个 MQ 消费者区分"瞬时重投/数据问题 ACK" |
| P0-9 乐观锁 | ✅ | `TicketSlaDO` @Version + escalate 原子条件更新（显式 UpdateWrapper，不依赖拦截器） |
| P0-10 越权/PII | ✅ | transition 归属校验 + 列表数据域隔离 + customerContact 脱敏（管理员明文）；4 个新单测 |
| P1-1 连接串环境变量化 | ✅ | `spring-dotenv` 加载本地 `.env`（gitignored）+ `.env.example` 入库模板；application.yml/dev/prod 全部变量化 |
| P1-2 日志分级 | ✅ | `logback-spring.xml`：Console + 滚动文件（按天/50MB/留 30 天），prod 仅 INFO |
| P1-3 acceptCategory 越权 | ✅ | 权限收窄为 `ticket:edit`（新增权限码）+ 乐观锁版本条件 + 归属校验 |
| P1-5 LLM 熔断/拆池 | ✅ | `LlmCircuitBreaker`（连续失败 5 次熔断 30s、半开探测、5s 硬超时）；AsyncConfig 拆 aiExecutor/coreExecutor 隔离池 |
| P1-6 分页封顶 | ✅ | `TicketQuery` size 封顶 100、page 非法兜底 |
| P1-7 看板聚合 | ✅ | Dashboard 全部指标下沉 SQL（GROUP BY / 条件 count / AVG(TIMESTAMPDIFF)） |
| P1-9 审计日志 | ✅ | `AuditService`（REQUIRES_NEW + 自动 IP/当前用户），埋点：登录成功/失败、登出、抢单、全部用户态流转、采纳分类 |
| P1-11 安全响应头 | ✅ | HSTS / frameOptions / CSP / NO_REFERRER |
| P1-12 首启幂等 | ✅ | 多副本并发初始化唯一键冲突兜底（uk_username / role-perm） |
| P1-8 .gitignore | ✅ | 忽略 `**/.env*`（保留 .env.example）+ dev/local yml |
| 验证 | ✅ | `mvn test` 52 用例全绿 + `vite build` 成功 |

> 剩余 P1 项：P1-4 已并入 P0-8 完成；P1-10 type claim 随 P0-7 完成。P2（Testcontainers 集成测试、前端工程化、低危安全项、部署文档）未动。

---

## 0. 总判定

**当前状态：架构素养高于普通 demo 的高质量单体原型，不可直接作为企业生产系统（尤其公网/多实例）。**

已确认的硬伤集中在四个方向：

1. **安全**：JWT secret 硬编码入库、渠道接口零鉴权、默认密码公开且打印日志、Swagger 全开、无登出入口。
2. **数据正确性**：SLA 补偿批次可被一张脏工单毒化导致全系统升级停摆；SLA 实体无乐观锁 + 定时任务无分布式锁（多实例会双重升级）；ES 双写删除语义缺失产生永久孤儿文档。
3. **前端**：401 刷新 single-flight 顺序错误导致首个请求永久挂死（真实 bug，已复现）。
4. **运维**：无 Dockerfile / CI / 监控 / 日志框架 / prod profile，dev 中间件绑定 Windows 本机。

同时，以下设计骨架是生产级、**不应在整改中退化**：状态机 CAS 单条 UPDATE、渠道建单唯一键幂等、refresh Lua 原子旋转、LLM 四场景降级、@PreAuthorize 全覆盖、无字符串拼接 SQL。

---

## 1. P0 阻断项（上线前必须解决）

> P0 = 存在真实触发路径的数据错误 / 安全沦陷 / 系统停摆。

### P0-1　JWT secret 硬编码入库，令牌可任意伪造

- **证据**：[application.yml:37-41](ticket-server/src/main/resources/application.yml#L39)
  ```yaml
  app:
    jwt:
      secret: ticket-ai-dev-secret-key-change-me-in-production-0123456789abcdef
  ```
  `application.yml` 已被 git 跟踪（`git ls-files` 确认），项目同时推在公开 GitHub（Attack12345/ticket-ai）。`JwtTokenProvider` 直接 `@Value` 读取私钥字节签名。
- **影响**：任何获得该常量的人可离线签发含 `ADMIN` 全权限码的合法 token，认证体系整体失效。生产若忘记覆盖，系统静默运行于"可伪造令牌"状态。
- **修复**：
  1. 改为 `${JWT_SECRET:}` 环境变量注入，默认空时禁止启动（失败快速暴露而非带病运行，对齐 LlmClient 对 api-key 的校验方式）；
  2. 修改后立即轮换签发密钥（旧 token 全部失效是预期行为）；
  3. 生产密钥长度 ≥ 32 字节随机值，建议 64 字节（现算法按 62 字节自动选 HS512，长度下限本身满足）。

### P0-2　对外渠道接口零鉴权、零限流 = 免费写库 DoS + LLM 账单放大器

- **证据**：
  - [SecurityConfig.java:38](ticket-server/src/main/java/com/ticketai/config/SecurityConfig.java#L38)：`"/api/v1/channels/web-api/tickets"` permitAll；
  - `ChannelController` 无任何 token/签名校验；`ChannelDO` 实体无 appKey/secret/token 字段（设计上就没有渠道凭证）；
  - 全工程 grep 无 RateLimit / Bucket4j / tryAcquire（无任何幂等之外的防刷）。
- **影响**：公网任意人可无限量建单，每单触发落库 + SLA 计时 + AI 分类 + 异步分派链路（[TicketServiceImpl.create:68-104](ticket-server/src/main/java/com/ticketai/service/impl/TicketServiceImpl.java#L68-L104)），等价于免费打爆数据库、烧爆 LLM 账单。
- **修复**：
  1. `channel` 表加 `app_key` / `app_secret`（或 HMAC 签名对），渠道请求用 `Authorization: Bearer <channelToken>` 走独立 Filter；
  2. Web API 渠道做**调用方限流**（Redis 令牌桶/固定窗口，按渠道维度），并增加**内容/频率黑名单**；
  3. 若业务上渠道来自可信内网域，至少用 nginx 层 IP 白名单兜底。

### P0-3　默认密码公开 + 明文打到日志，且无首登改密 / 无防爆破 / 无用户管理

- **证据**：
  - [DataInitializer.java:36-58](ticket-server/src/main/java/com/ticketai/config/DataInitializer.java#L36-L58)：`DEFAULT_PASSWORD = "Admin@12345"`，admin/agent01 共用同一枚 BCrypt，且 `log.info("初始化完成：创建 admin / agent01（密码 {}）")` 明文打印；
  - `AuthServiceImpl.login` 无失败计数/锁定/验证码/IP 限流（配合公开密码 = 在线爆破零成本）；
  - 全工程**无用户管理接口**（无 SysUserController），忘记手工改库 = 永久后门。
- **修复**：
  1. 按 `@Profile("dev")` 或环境开关隔离初始化，生产 profile 不播种演示账号；
  2. 移除口令日志（只打印用户名）；
  3. 新增"首次登录强制改密"标志字段或独立首启接口；补 `sys_user` 管理 CRUD；
  4. 登录失败 5 次锁定 N 分钟（Redis INCR + TTL）。

### P0-4　【代码级 bug】SLA 补偿批次可被单条脏数据毒化，全部超时升级永久停摆

- **证据链**（已在本地复核，全部属实）：
  1. [SlaServiceImpl.compensate:119-143](ticket-server/src/main/java/com/ticketai/service/impl/SlaServiceImpl.java#L119-L143)：`@Transactional` 整批一个事务，循环内**逐条直调** `handleDelayCheck`，无 per-record try/catch、无 `REQUIRES_NEW`；
  2. `escalate()` 在事务内**同步** `eventPublisher.publishEvent(new SlaTimeoutEvent(...))`（[SlaServiceImpl.java:181](ticket-server/src/main/java/com/ticketai/service/impl/SlaServiceImpl.java#L181)）；
  3. 事件监听 `onSlaTimeout` → `transition(TIMEOUT_ESCALATE)`，而 `TIMEOUT_ESCALATE` 只在 PENDING_ASSIGN/PROCESSING/WAITING_CUSTOMER 注册（[StateMachineRegistry.java:25,31,37](ticket-server/src/main/java/com/ticketai/state/StateMachineRegistry.java#L25)）。对 RESOLVED/CLOSED/CANCELLED 触发即抛 `ILLEGAL_TRANSITION`；
  4. 脏数据产生路径完全合法：PROCESSING **直接 RESOLVE**（[StateMachineRegistry.java:29](ticket-server/src/main/java/com/ticketai/state/StateMachineRegistry.java#L29)）或 CANCEL，坐席从未 REPLY → `ticket.first_responded_at` / `ticket_sla` 永不结算（resolve/cancel 无 SLA 结算钩子）→ 该 SLA 行符合补偿扫描条件（`escalationTriggered=0 AND firstResponseStatus=0 AND deadline < now`）被反复捞出；
  5. 异常穿出 → **回滚整个补偿事务**（连同已置位的 `escalationTriggered=1`）→ 下一轮又扫到 → 其余真正超时的工单**永远得不到升级**，永久饥饿。
  - 现有单测 `SlaServiceImplTest.compensateCatchesLostMessage` 用的是合法状态的工单，恰好无法暴露此缺陷。
- **修复**（任一组合，建议全做）：
  1. `compensate()` 每条循环体 try/catch 隔离（或 `handleDelayCheck` 提升 `@Transactional(REQUIRES_NEW)`）；
  2. `escalate()` 升级前先 `StateMachine.find(ticket.status, TIMEOUT_ESCALATE)` 预判，非法则只结算导航状态（`escapedTriggered=1` + 结算 status），不触发状态机；
  3. 为常规"解决/关闭/取消"路径补 SLA 结算钩子（transition 后置动作里同步 `markSettled`），从源头减少脏 SLA 行；
  4. 给 `TicketSlaDO` 加 `@Version` 乐观锁（见 P1-3）。

### P0-5　【代码级 bug】前端 401 刷新 single-flight 顺序写反，首个触发请求永久挂死

- **证据**：[request.js:44-63](frontend/src/api/request.js#L44-L63)。请求 A 第一个撞 401：进入 `if (!refreshing)` 分支 → 刷新成功 → `waiters.forEach` flush（此刻 flush 的只是刷新期间排队加入的 B/C）→ `waiters=[]` → A 走到 `await new Promise(resolve => waiters.push(resolve))` 把自己的 resolver 推进**已被清空的数组**，此后无人再 flush → **A 永久 pending，对应页面 loading 卡死。**
- **影响**：每次会话内第一次遇到 401（access token 过期，30 分钟一次）就有请求永久挂起；体验为"登录后首次操作卡住"。
- **修复**：把"第一个请求自己处理刷新结果"的逻辑从 waiters 中分出——典型写法：单独变量 `firstResolve`，或 A 自己在刷新后直接重放成功，waiters 只服务后续并发者：

  ```js
  if (!refreshing) {
    refreshing = true
    try {
      const data = await axios.post('/api/v1/auth/refresh', {...})
      userStore.setTokens(data.data.accessToken, data.data.refreshToken)
      waiters.forEach(w => w(true)); waiters = []
      return request(config)      // A 自己直接重放
    } catch (e) { ... }
  }
  const ok = await new Promise(resolve => waiters.push(resolve))
  if (ok) return request(config)
  ```

### P0-6　Swagger/Knife4j 全环境匿名放行 + 无 profile 区分

- **证据**：[SecurityConfig.java:39-44](ticket-server/src/main/java/com/ticketai/config/SecurityConfig.java#L39-L44) 将 `/doc.html` `/swagger-ui/**` `/v3/api-docs/**` `/webjars/**` 长期 permitAll；`application.yml` `springdoc.api-docs.enabled: true`。项目**只有 dev 一套配置**，无 `application-prod.yml`。
- **影响**：公网直接白屏化暴露全部 34 个接口的参数结构（含管理接口），为 P0-2/P0-3 的攻击面开图。
- **修复**：补 `application-prod.yml`，prod 下 `springdoc.api-docs.enabled: false`；或 SecurityConfig 内按 profile 条件放行。

### P0-7　无登出接口、access token 无法吊销；refresh token 明文存 Redis

- **证据**：全工程 grep 无 logout/api/blacklist；"退出"仅前端清 localStorage（[user.js:29-32](frontend/src/stores/user.js#L29-L32)）；改密/禁用员工不影响已签发的 30 分钟 access token；Redis 中存的是完整 JWT 字符串（[AuthServiceImpl:137-141](ticket-server/src/main/java/com/ticketai/service/impl/AuthServiceImpl.java#L137-L141)）。
- **影响**：账号被盗/员工离职后，其已持有的会话在 30 分钟（access）+ 7 天（refresh）内不可撤销。
- **修复**：
  1. 新增 `POST /api/v1/auth/logout`：删除 Redis refresh key（Lua），并把 access token 的 `jti` 加入 Redis 黑名单（TTL=剩余有效期）；
  2. 签发的 access token 增加 `jti` claim；JwtAuthenticationFilter 校验时查黑名单；
  3. Redis 只存 refresh token 的 **SHA-256 摘要**。

### P0-8　ES 双写一致性缺陷：update 产生永久孤儿文档、对账单向、ticket_index 无兜底

- **证据**（双链路可靠性差异明显）：
  1. **知识库**：更新路径 [KnowledgeBaseServiceImpl:81-97](ticket-server/src/main/java/com/ticketai/service/impl/KnowledgeBaseServiceImpl.java#L81-L97) 删 MySQL 旧分段、插入**新 id** 分段，**从不删旧 ES doc**；对账任务 `EsSyncCompensationTask` 只做 mysql→es 求缺，**不做 es→mysql 求冗** → 每次编辑累积陈旧可检索文档，搜索结果逐次变脏；
  2. **已解决工单索引**：afterCommit 异步 `@EventListener` 写入 [TicketIndexService:36-66](ticket-server/src/main/java/com/ticketai/service/impl/TicketIndexService.java#L36-L66)，写失败仅 `log.warn`，**无重试、无对账** → 相似工单召回静默漏样本；
  3. 两个消费者（[SlaDelayConsumer:26-36](ticket-server/src/main/java/com/ticketai/mq/SlaDelayConsumer.java#L26-L36)、[EsSyncRetryConsumer:27-40](ticket-server/src/main/java/com/ticketai/mq/EsSyncRetryConsumer.java#L27-L40)）catch 后正常返回（= ACK），吞掉异常 → RocketMQ 默认 16 次重投/死信**实际全部失效**（es-sync-retry 名不副实）。
- **修复**：
  1. update 前先按旧分段 id 删除 ES 文档（或按 content 指纹 upsert）；
  2. 对账任务补反向扫描（es→mysql 求冗删除）与 ticket_index 对账；
  3. 消费者区分"瞬时故障可重试（直接抛出，交给 RocketMQ 重投）"与"数据问题不可重试（落死信 + 告警）"；为 es-sync-retry 配 DLQ 与告警通道（开发期至少一行 `log.error` 计指标）。

### P0-9　定时任务无分布式锁 + SLA 实体无乐观锁（多实例会双重升级）

- **证据**：
  - 全仓仅两处 `@Scheduled`（`SlaCompensationTask`、`EsSyncCompensationTask`），无 ShedLock/Redis 锁 → 每副本都执行；
  - `TicketSlaDO` 无 `@Version`（[TicketSlaDO:15-48](ticket-server/src/main/java/com/ticketai/entity/TicketSlaDO.java#L15-L48)），`escalate()` 的 `escalationTriggered` 读后写非原子 → 多副本并发补偿/消费可**双重升级**（双审计、双状态日志）。
- **修复**：引入 `spring-integration-jdbc`/ShedLock 或 Redisson 分布式锁包裹两个调度任务；`TicketSlaDO` 加 `@Version`，升级 SQL 带版本条件（改 `UpdateWrapper` 显式拼接，沿用 DEV_DOC §4.2 的既有方案）。

### P0-10　横向越权：坐席可操作任何工单，列表无数据域隔离，客户 PII 无脱敏

- **证据**：[TicketServiceImpl.transition:241-256](ticket-server/src/main/java/com/ticketai/service/impl/TicketServiceImpl.java#L241-L256) 只校验权限码，**不比较 `ticket.agent_id` 与当前用户 agentId**；AGENT 角色默认拥有 reply/resolve/close 全部权限码；`pageList`（[:207-226](ticket-server/src/main/java/com/ticketai/service/impl/TicketServiceImpl.java#L207-L226)）全员可见全量工单含 `customerName/customerContact`。
- **影响**：任何坐席可回复、解决、关闭他人工单；客户联系方式对全员暴露。
- **修复**：transition 类操作在已分配工单上校验 `agent_id == 当前 agentId`（管理员与 SYSTEM 事件除外）；列表接口按角色做数据域过滤（坐席只见分配给自己 or 未分配可抢的）；若共享工作台是产品决策，至少对 `customerContact` 做脱敏显示。

---

## 2. P1 中危（稳定性 / 可运维性）

| # | 问题 | 证据 | 修复建议 |
|---|---|---|---|
| P1-1 | 无 `application-prod.yml`，中间件地址写在被跟踪文件里（`127.0.0.1:9876`、`http://127.0.0.1:9200`） | [application.yml:33,50](ticket-server/src/main/resources/application.yml#L33) | 全量连接串走 `${ENV:...}` 占位符，补 prod profile |
| P1-2 | `log-impl: StdOutImpl` + `com.ticketai: debug` 全环境输出 SQL（含参数值） | [application.yml:15-18,54-56](ticket-server/src/main/resources/application.yml#L15-L18) | prod 关闭 SQL 输出；补 logback-spring.xml，按 profile 分级 |
| P1-3 | `acceptCategory` 裸 update 无版本条件，且仅 `ticket:view` 权限即可改任意工单分类 | [TicketServiceImpl:194-204](ticket-server/src/main/java/com/ticketai/service/impl/TicketServiceImpl.java#L194-L204) | 加 version 条件 + 收窄权限（`ticket:edit`） |
| P1-4 | 知识库 MySQL→ES 双写在事务内**同步**跨 N 次 ES + embedding 网络调用 = 长事务 | [KnowledgeBaseServiceImpl:144-166](ticket-server/src/main/java/com/ticketai/service/impl/KnowledgeBaseServiceImpl.java#L144-L166) | ES/向量写入移出事务：落库后 afterCommit 异步 + MQ 重试 |
| P1-5 | LLM 无熔断/舱壁；异步任务共用一个线程池（core2/max4/queue200），LLM 慢会打满拖垮自动分派 | [AsyncConfig:18-28](ticket-server/src/main/java/com/ticketai/config/AsyncConfig.java#L18-L28) | 接入 Resilience4j（TimeLimiter + 熔断）；AI 与核心任务拆池 |
| P1-6 | 分页无 size 上限（`size=1000000` 可全表拉取） | [TicketQuery:28-30](ticket-server/src/main/java/com/ticketai/query/TicketQuery.java#L28-L30) | `size` 封顶（如 100），排序字段白名单已正确 |
| P1-7 | 看板 `selectList(null)` 全表入内存聚合，数据量线性劣化、OOM 风险 | [DashboardServiceImpl:33-75](ticket-server/src/main/java/com/ticketai/service/impl/DashboardServiceImpl.java#L33-L75) | 改 SQL 聚合（group by status 等），SLA 按时率用条件 count |
| P1-8 | `.gitignore` 未排除 `application-dev.yml`（注释却声称已被排除），`root/1234` 有被提交危险 | [.gitignore](.gitignore) | 追加 `*application*.yml` 中的 dev 文件排除项 / 移出跟踪 |
| P1-9 | 审计日志覆盖残缺：全工程唯一写入点为 SLA 升级，登录失败、403、抢单、派单、策略变更均无审计 | [SlaServiceImpl:169-177](ticket-server/src/main/java/com/ticketai/service/impl/SlaServiceImpl.java#L169-L177) | 按 DEV_DOC 规划的 `@Audited` AOP 补齐关键动作（M7 未完成项） |
| P1-10 | token 无 `type` claim，access 可当 refresh 洗成 7 天凭证对；refresh 可当 access 用（authorities 为空） | [JwtTokenProvider:42-57](ticket-server/src/main/java/com/ticketai/security/JwtTokenProvider.java#L42-L57) | build() 加 `type=access/refresh`；parse 与 refresh 路径校验类型 |
| P1-11 | token 存 localStorage + 后端零安全响应头（无 CSP/HSTS/X-Frame-Options） | [user.js:6-7](frontend/src/stores/user.js#L6-L7)；[SecurityConfig](ticket-server/src/main/java/com/ticketai/config/SecurityConfig.java) | 迁移到 HttpOnly cookie（或至少加 CSP + 前端 XSS 第二道防线）；SecurityFilterChain 补响应头 |
| P1-12 | `DataInitializer` 的幂等守卫基于"表非空"，多副本并发首启可能双双通过 → 重复插入 | [DataInitializer:69,102](ticket-server/src/main/java/com/ticketai/config/DataInitializer.java#L69) | 初始化加分布式锁或用 SQL 唯一键兜底（已有 `uk_username` 则捕获冲突即可） |

---

## 3. P2 工程完备度（低危，上线后迭代）

- **零集成测试**：39 个单测全部 Mockito、无 `@SpringBootTest`/Testcontainers。真实事务代理、MyBatis SQL、MQ 装配、ES 查询、`@Scheduled`、鉴权过滤器**从未被执行**；`TicketClaimConcurrentTest` 把 Redisson 锁 mock 掉、把"只有首发返回 1"写死为 mock 契约，只能证明"update 返回 0 时代码抛 CONCURRENT_MODIFY"，**不能**证明真实 DB 行锁确保证只有 1 个赢家。→ 引入 Testcontainers（MySQL/Redis/RocketMQ/ES）补端到端用例，优先覆盖 P0-4/P0-8/P0-9。
- **注释与实现漂移**：`EsSyncRetryConsumer` 注释"进重试队列"实际吞异常即 ACK；`MetaObjectHandler` 无任何 `@TableField(fill=...)` 实体配合，实为 no-op（时间戳全靠手写，易误导后人）。
- **前端工程化**：无 lint/format/测试；`hasPermission` 定义后全工程零调用（权限 UI 缺失，靠后端 34 处 @PreAuthorize 兜底，无越权但体验差）；`TicketDetail` 操作按钮无防连击（可重复插评论，状态机能挡但数据脏）；full import ECharts + 全局注册全部图标（首包偏大）。
- **低危安全项**：用户名枚举（禁用/不存在的返回文案不同）；`LoginDTO` 无长度上限；`BusinessException` 把含内部 id 的 message 原样回传前端；`infra/broker.conf;C` 空目录残渣入库（应删除）。
- **部署文档缺失**：根 README 只有功能宣传无安装/部署章节；ticket-server/README.md 仅 4 字节；无 Dockerfile、无 nginx 站点配置、无服务器规格说明。

---

## 4. 修复路线图（建议排期）

### 阶段 A —— 当天能修（约 1 天，稳赚）

| 项 | 内容 |
|---|---|
| P0-5 | 前端 refresh 死锁（半小时） |
| P0-4 | SLA 补偿逐条隔离 + 升级前 `canTransition` 预判（半天，数据正确性红线） |
| P0-1 | JWT secret 改环境变量（1 小时） |
| P1-8 | .gitignore 补 application-dev.yml（5 分钟，防后悔） |

### 阶段 B —— 一经修复可上线内网单实例（约 1 周）

| 项 | 内容 |
|---|---|
| P0-6 | prod profile + 关闭 swagger |
| P0-3 | 生产不播种演示账号 + 移除口令日志 + 登录锁定 |
| P0-2 | 渠道 appKey 鉴权 + 限流 |
| P0-7 | 登出 + jti 黑名单 + refresh 摘要存储 |
| P0-10 | 数据域隔离 + PII 脱敏 |
| P1-1 / P1-2 / P1-11 | 连接串环境变量化、日志分级、安全响应头 |

### 阶段 C —— 多实例 / 公网上线（约 2 周）

| 项 | 内容 |
|---|---|
| P0-9 | 定时任务分布式锁 + TicketSlaDO 乐观锁 |
| P0-8 | ES 双向对账 + 删除语义 + 消费重试/DLQ |
| P1-4 / P1-5 | 双写移出事务、LLM 熔断、拆线程池 |
| P1-6 / P1-7 | 分页封顶 + 看板改 SQL 聚合 |
| 运维 | Dockerfile + CI/CD + actuator/health + logback + Testcontainers 集成测试 |

---

## 5. 验收口径（对照"能否真实使用"）

上线前至少通过以下检查，才能宣称"企业真实可用"：

- [ ] 生产中 JWT secret 来自环境变量，且 `git grep` 全仓库无任何密钥/口令字面量
- [ ] 渠道接口：匿名调用在无 appKey 时返回 401；限流生效
- [ ] 演示账号不出现在生产环境；重启前后口令日志均无明文
- [ ] 一张"未回复即解决"的超期工单存在时，补偿扫描仍能升级**其他**工单（复现 P0-4 后验证）——该用例进自动化测试
- [ ] 无登录 token 过期场景下首次 401 后页面不再卡死（复现 P0-5 后验证）
- [ ] 双实例部署时 SLA/对账任务不重复执行、无双重升级审计
- [ ] 知识库编辑两次后 ES 搜索结果不含陈旧片段
- [ ] 坐席 A 无法操作坐席 B 的已分配工单；列表接口对非管理员可见字段不含明文联系方式
- [ ] 40+ 接口在 `mvn test` + Testcontainers 集成测试下全绿