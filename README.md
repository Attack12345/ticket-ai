# TicketAI 企业智能客服工单系统

AI 增强的企业级客服工单系统：多渠道接入客户请求，工单全生命周期管理，SLA 超时自动升级，LLM 自动分类与 RAG 回复建议。

## 功能特性

- **工单全生命周期**：创建 → 自动分派 → 抢单 → 处理 → 解决 → 关闭，状态机驱动
- **SLA 引擎**：RocketMQ 延迟消息精确到点结算 + 每 5 分钟补偿扫描双保险，超时自动升级并落审计
- **分派策略**：轮询 / 负载最低 / 技能匹配 / AI 推荐四种策略，按权重降序自动尝试，抢单采用 Redisson 锁 + 乐观锁双保险
- **AI 增强**（可降级）：工单自动分类（SETNX 防重）、RAG 回复建议、AI 分派推荐；LLM 不可用系统全流程照常
- **检索**：Elasticsearch 全文 + dense_vector 语义双路检索，双写一致性
- **管理后台**：坐席 / 技能组 / SLA 策略 / 知识库 / 统计看板

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.5 · JDK 23（编译目标 21）· MyBatis-Plus 3.5 |
| 中间件 | MySQL 8.0 · Redis · RocketMQ 5.3（延迟消息）· Elasticsearch 8.14（全文 + 向量） |
| 安全 | JWT 双 token（Lua 原子刷新旋转）+ RBAC 权限码 |
| 前端 | Vue 3 · Vite 5 · Element Plus · Pinia · ECharts |
| AI | OpenAI 兼容协议自研客户端（chat / embeddings），每次调用记录 token 用量 |

## 系统架构

```mermaid
flowchart LR
    subgraph 接入层
        A1[Web API 渠道] --> A2[幂等创建]
        A3[内部创建] --> A2
    end
    A2 --> B[工单域<br/>状态机 8×12]
    B --> C[SLA 引擎]
    C --> C1[RocketMQ 延迟消息]
    C --> C2[补偿扫描 5min]
    B --> D[分派引擎]
    D --> D1[轮询/负载/技能/AI]
    D --> D2[抢单: Redisson锁+乐观锁]
    B --> E[AI 增强]
    E --> E1[自动分类 SETNX]
    E --> E2[RAG 回复建议]
    E --> E3[AI 分派推荐]
    B --> F[(MySQL)]
    C1 --> F
    E2 --> G[(Elasticsearch<br/>全文+向量)]
    B --> H[审计日志]
    F --> I[统计看板]
```
