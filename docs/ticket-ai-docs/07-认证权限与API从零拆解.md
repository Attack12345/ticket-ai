# TicketAI 学习手册 · 第 7 篇：认证权限与 API 从零拆解

> **本篇目标**：看完后你能——① 分清"认证"和"授权"；② 讲清 Session 和 JWT 的取舍、为什么双 token；③ 解释刷新为什么必须用 Lua 原子脚本；④ 看懂"请求进来 → 认证 → 授权"的完整链路；⑤ 背出项目的主要接口。
>
> 阅读前提：第 2 篇的 RBAC 五表。

---

## 7.1 先分清两个概念：认证 vs 授权

- **认证（Authentication）**：门卫验证"你是谁"——拿出工牌，核对是本人。
- **授权（Authorization）**：门禁验证"你能进哪层楼"——工牌显示你有 3 层权限。

登录接口做认证（核对密码）；`@PreAuthorize` 和状态机里的 checkPermission 做授权（核对权限码）。**先认证，后授权**——认错人谈权限没意义。

---

## 7.2 为什么用 Token 而不是 Session？

### 7.2.1 传统 Session 方案

用户登录 → 服务器内存里存一份"会话记录"（张三已登录）→ 返回一个 sessionId → 浏览器之后每次请求带上 sessionId → 服务器查内存验证。

**问题**：
1. 服务器**有状态**了——多台服务器部署时，用户登录在 A 机器，请求却打到 B 机器，B 没有他的会话记录 → 得做 session 共享（存 Redis）或粘性会话。
2. 会话记录全堆在服务器内存，用户多了内存压力大。

### 7.2.2 JWT 方案（无状态）

用户登录 → 服务器**签发一张"卡片"（JWT）**→ 卡片里写死"我是谁、我有什么权限、什么时候过期"，并用密钥签名（防伪造）→ 之后每次请求带卡片 → 服务器**只验签名不查库**——签名合法、没过期，就认。

**核心区别**：Session 的验证信息在**服务器**；JWT 的验证信息在**卡片本身**。服务器不用存任何登录状态（无状态），天然支持多台服务器（任何一台都能验签名）。

### 7.2.3 JWT 长什么样

```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwidXNlcm5hbWUiOiJhZG1pbiJ9.5mhIC... 
```

三个部分用点号分隔：
1. **Header**（头）：用的什么算法（HS256）。
2. **Payload**（载荷）：数据——userId、username、agentId、permissions（权限码列表）、签发时间、过期时间。
3. **Signature**（签名）：用密钥对"头+载荷"算的签名——**别人改一个字节，签名就对不上，直接失效**（防篡改）。

**注意**：payload 是 base64 编码，**不是加密**——任何人都能解码看到内容。所以**敏感信息（密码）绝不能放 JWT**，放了签名也白搭（能看）。

---

## 7.3 为什么双 token？（access + refresh）

一张卡片的矛盾：**有效期短**（被盗损失小，但用户老要重新登录）vs **有效期长**（体验好，但被盗损失大）。

解法：两张卡分工。

| | accessToken | refreshToken |
|---|---|---|
| 有效期 | 30 分钟 | 7 天 |
| 作用 | 每次请求带它做认证 | 只用于"换新卡"（调刷新接口） |
| 泄露风险 | 30 分钟窗口 | 存服务器 Redis，可作废 |

流程：
```
登录 → 发两张卡
请求 → 带 accessToken（30分钟）
accessToken 过期 → 前端拿 refreshToken 调 /auth/refresh → 换一对新卡
refreshToken 也过期 → 重新登录
```

**为什么 refreshToken 要存 Redis？** 两个原因：
1. **可作废**：改密码/安全事件时删掉 Redis 里的 refreshToken，该用户所有会话立即失效（JWT 本身无法作废，只能等过期）。
2. **单设备语义**：新登录覆盖旧 token——旧设备再用旧 refreshToken 就失败（"滚动替换"）。

---

## 7.4 刷新为什么必须用 Lua？（面试必考细节）

### 7.4.1 普通写法的漏洞

刷新流程三步：① 读 Redis 里的 token ② 比对是否和请求的一致 ③ 一致则删除旧的、签发新的。

Java 代码分三步写（读→比→删）的问题：**并发**。黑客偷了旧 refreshToken，同时发两个刷新请求：

```
请求A：读到 token=X → 比对一致 → 删除 X → 签发新卡A
请求B：读到 token=X → 比对一致 → 删除 X → 签发新卡B   ← B 也成功了！
```

结果：A 和 B 都成功——**旧 token 被用了两次**，黑客的旧卡也能刷新，安全防线被击穿。

### 7.4.2 Lua 方案：把三步合成一步

Redis 支持执行 Lua 脚本（Redis 单线程执行脚本，**脚本内的操作天然原子**，中间不可能插入其他命令）：

```lua
-- 参数：KEYS[1]=refresh:{userId}，ARGV[1]=请求带来的 refreshToken
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])   -- 一致：删除，返回 1
else
    return 0                             -- 不一致：返回 0
end
```

两个并发请求执行同一个脚本：**Redis 单线程依次执行**——第一个执行完 key 已删，第二个进来 get 不到（或得到 null），返回 0 → 拒绝。**只有一个能成功。**

代码里的样子：

```java
Long deleted = stringRedisTemplate.execute(
        COMPARE_AND_DELETE, List.of("refresh:" + userId), dto.getRefreshToken());
if (deleted == null || deleted != 1L) {
    throw new BusinessException(ErrorCode.UNAUTHORIZED, "refreshToken 已失效，请重新登录");
}
// 删除成功 → 签发新的一对 token
```

**这个设计的一句话总结**："**读-比-删三步必须原子，Java 分步有竞态窗口，Redis Lua 单线程执行天然原子。**"——面试官听了会点头的设计。

---

## 7.5 请求认证链路：从进门到放行

```
请求带 Authorization: Bearer <accessToken>
  │
  ▼
JwtAuthenticationFilter（过滤器，每个请求先经过它）
  ├─ 没有 token → 放行（后面由接口决定要不要认证）
  ├─ 有 token → 验签名 + 验过期
  │     ├─ 无效/过期 → 返回 401
  │     └─ 有效 → 解析出 LoginUser(userId, username, agentId, permissions)
  │              → 放进 UserContextHolder（ThreadLocal 上下文）
  │              → 放行
  ▼
Controller 接口（@PreAuthorize 注解做授权）
  └─ @PreAuthorize("hasAuthority('ticket:claim')")：检查 LoginUser 的权限码列表
  ▼
Service 层（状态机内嵌授权，第二道闸）
  └─ transition() 的 checkPermission(requiredPermission)（见第 3 篇）
```

**为什么有两道授权？**
- `@PreAuthorize`：接口层闸门——防"无权限用户调接口"。
- 状态机 checkPermission：核心链路闸门——**就算有人绕过 Controller 直接调 service（比如内部代码 bug），状态机的矩阵权限要求依然生效**。权限要求写在状态机矩阵里，是业务规则的一部分，不是接口装饰。

**UserContextHolder 是什么**：ThreadLocal——每个请求线程独立的"小口袋"，放当前登录用户。Service 层从口袋拿 userId/username/agentId，**不依赖 HttpServletRequest**（这保证了分层：Service 不碰 HTTP 细节，测试也好 mock）。

**权限码从哪来**：登录/刷新时查 RBAC 五表（用户→角色→权限），打包进 accessToken 的 claim。之后每次请求从 token 里读——**无状态，不查库**。代价：改权限要等 token 过期（30 分钟）才生效——可接受的权衡（文档里也设计了 5 分钟权限缓存刷新方案）。

**白名单**（不用认证就能访问）：登录、刷新、**客户渠道建单**（客户没有系统账号，天然公开，靠 message_no 幂等防重复）、接口文档、错误页。

---

## 7.6 审计：敏感操作留痕

谁在什么时间对什么对象做了什么，必须能查。

```java
// 注解式 AOP：方法上标一下，切面自动写 audit_log
@Audited(action = "TICKET_ASSIGN")
public void assign(...) { ... }
```

审计表记录：action（TICKET_ASSIGN 分派 / TICKET_CLOSE 关闭 / SLA_ESCALATE 系统升级…）、target_type/target_id（对哪张工单）、detail_json（详情）、ip、时间。

**注意 SLA 升级也有审计**——那是"系统"操作的（username=system），说明**系统行为和人一样要留痕**。

---

## 7.7 API 全景（背下来，面试画接口如数家珍）

统一规则：前缀 `/api/v1`、返回 `Result<T>`（code/message/data/timestamp）、分页返回 `{records,total,page,size,pages}`、认证头 `Authorization: Bearer`。

**错误码表**（9 个）：

| code | 含义 | 典型场景 |
|---|---|---|
| 400 | 参数错误 | 校验失败 |
| 401 | 未认证 | token 无效/过期、密码错 |
| 403 | 无权限 | 权限码不足、账号禁用 |
| 404 | 不存在 | 工单查不到 |
| 409 | 并发冲突 | 乐观锁失败、非法流转、消息重复 |
| 500 | 系统异常 | 兜底 |
| 503 | AI 不可用 | LLM 挂了（回复建议场景） |

**认证**：
```
POST /api/v1/auth/login      {username, password} → {accessToken, refreshToken}
POST /api/v1/auth/refresh    {refreshToken} → 新的一对
```

**工单**（最核心的一组）：
```
GET    /api/v1/tickets                        列表（状态/优先级/分类/客服/关键词/时间筛选+分页）
GET    /api/v1/tickets/{id}                   详情（含 SLA 信息、allowedEvents 可操作事件）
GET    /api/v1/tickets/{id}/timeline          时间线（状态流水 + 评论合并）
POST   /api/v1/tickets                        创建工单
POST   /api/v1/tickets/{id}/claim             抢单
POST   /api/v1/tickets/{id}/assign            手动分派 {agentId}
POST   /api/v1/tickets/{id}/reply             回复 {content, visibility}
POST   /api/v1/tickets/{id}/resolve           解决
POST   /api/v1/tickets/{id}/close             关闭
POST   /api/v1/tickets/{id}/reopen            重开
POST   /api/v1/tickets/{id}/escalate          人工升级
POST   /api/v1/tickets/{id}/cancel            取消
POST   /api/v1/tickets/{id}/ai-suggest        AI 回复建议 → {reply, kbRefs}
POST   /api/v1/tickets/{id}/accept-category   采纳 AI 分类 {category, priority}
```

**渠道**（公开）：
```
POST /api/v1/channels/web-api/tickets  客户建单 {messageNo, ...}，messageNo 重复返回已建工单
```

**管理**：agents（坐席）、skill-groups（技能组）、sla-policies（SLA 策略）、kb（知识库 + kb/search 检索）、dashboard/stats（看板：各状态数量、SLA 按时率、平均首响分钟、今日新增/解决）。

---

## 7.8 本篇小结

- 认证 = 你是谁；授权 = 你能干什么。
- JWT = 带签名的自验证卡片（无状态、防篡改、多实例友好）；敏感信息不放 payload。
- 双 token：access 短命防泄露、refresh 长命换新卡；refresh 存 Redis 可作废、单设备滚动。
- **刷新必须 Lua 原子**：读-比-删三步有并发竞态，Redis 单线程脚本天然原子。
- 授权两层：接口 @PreAuthorize + 状态机 checkPermission（核心链路闸门）。
- 权限码打包进 token，无状态校验；改权限 30 分钟生效（权衡）。
- 敏感操作全部审计，系统操作也审计。
- API：认证 2 个 + 工单 16 个 + 渠道 1 个 + 管理 5 组 + 看板 1 个。

---

# 学习系列完结 · 下一步

你已经读完了全部 7 篇。建议复盘路径：
1. 回看第 1 篇的"一次请求完整旅程"——现在你应该能解释每一步背后的机制了。
2. 合上文档，用大白话把四个核心模块讲一遍（状态机 / SLA / 并发 / AI），卡住的地方回去翻对应篇章。
3. 打开《面试题》三篇，遮住答案自测。

**面试题系列**：
- 面试题 01 · 整体与数据库（45 题）
- 面试题 02 · 核心机制（状态机 / SLA / 并发 / 分派）
- 面试题 03 · 检索 AI 认证与系统设计
