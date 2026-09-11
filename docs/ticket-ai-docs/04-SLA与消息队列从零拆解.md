# TicketAI 学习手册 · 第 4 篇：SLA 与消息队列从零拆解

> **本篇目标**：看完后你能——① 用外卖例子讲清楚 SLA 是什么；② 解释为什么"延迟消息 + 补偿扫描"缺一不可；③ 完整画出 SLA 从建单到升级的流程图；④ 讲明白每一步的幂等设计；⑤ 看懂核心代码。
>
> 阅读前提：第 2 篇的 sla_policy / ticket_sla 表；第 3 篇的状态机（超时升级走 TIMEOUT_ESCALATE 事件）。

---

## 4.1 先理解问题：什么叫"服务承诺"？

### 4.1.1 外卖的 30 分钟承诺

外卖平台承诺"30 分钟送达"。平台怎么知道哪单超时了？

**方案 A（人肉盯）**：组长盯着每单的时间，看到快超时的喊一嗓子——订单多了根本盯不过来。

**方案 B（定时轮询）**：写个程序每 1 分钟把所有"还没送达"的订单扫一遍，看谁超了 30 分钟——简单，但每 1 分钟扫全量订单，订单多了开销大；而且"30 分钟整"检查变成了"30 分到 31 分之间"，不准时。

**方案 C（到点叫我）**：每笔订单下单时，登记"这笔 30 分钟后该检查"，系统到点**主动**提醒——精准、开销小，但万一提醒丢了（服务器重启、消息丢失）呢？没人检查了。

聪明的系统：**C 为主 + B 兜底**。这就是本项目的方案——延迟消息保证"准时"，补偿扫描保证"不丢"。先把这两个概念讲透。

### 4.1.2 SLA 是什么

SLA（Service Level Agreement，服务等级协议）——**"我承诺在多长时间内做到什么"的合同**。

本项目两档承诺：
- **首次响应承诺**：客户进线后，X 分钟内必须有客服回复（哪怕是"我们已收到，正在处理"）。
- **解决承诺**：Y 分钟内必须解决。

超时的后果：**自动升级**（工单状态变 ESCALATED，登记审计，通知组长）。

---

## 4.2 补 MQ 基础：消息队列是"快递驿站"

### 4.2.1 生产者 / 消费者 / Topic

- **生产者（Producer）**：发消息的人（如"工单服务"投递"工单创建了"）。
- **消费者（Consumer）**：收消息处理的人（如"分派模块"监听"工单创建了"）。
- **Topic（主题）**：消息的分类名，像快递站的不同货架。本项目有 `ticket-sla-check`（SLA 检查）、`es-sync-retry`（ES 重试）等。

**关键特性：生产者发完就完事，不等待消费者处理完**——这就是"异步解耦"。

### 4.2.2 普通消息 vs 延迟消息

- **普通消息**：发了马上能收。
- **延迟消息**：**到点才投递**。比如发一条"2 小时后检查工单 5 号"的消息，消费者 2 小时后才收到它。

RocketMQ 5.x 的用法：

```java
Message message = new Message("ticket-sla-check", "FIRST_RESPONSE", 消息体);
message.setDeliverTimeMs(截止时间的毫秒时间戳);   // 核心：绝对时间戳，到点投递
producer.send(message);
```

（4.x 只有 18 个固定延迟等级，5.x 支持任意时间——这是选型 5.x 的原因之一。）

### 4.2.3 为什么要"异步"而不直接在代码里 Thread.sleep(2小时)？

**Thread.sleep 是把检查任务放在"发消息的人"的线程里等 2 小时**——线程被占死，工单创建接口每来一个请求就占一个线程等 2 小时，服务器瞬间被打爆。**延迟消息是"把到点执行的责任交给消息中间件"**，发完消息生产者线程立刻释放。这是异步思想的精髓：**别自己等，让系统到点叫你**。

---

## 4.3 全景图：SLA 从建单到升级的完整流程

```
【工单创建时】（事务内）
  1. 按工单优先级查启用中的 SLA 策略
     例：优先级=中 → 找到"中"策略：首响 120 分钟、解决 1440 分钟
  2. 算截止时间：firstResponseDeadline = 现在 + 120分钟
                   resolveDeadline      = 现在 + 1440分钟
  3. 插入 ticket_sla 计时单（把算好的截止时间固化下来）
  4. 投两条延迟消息（到点检查）：
     {检查类型: FIRST_RESPONSE, 到点: 首响截止}
     {检查类型: RESOLVE,       到点: 解决截止}
  （消息发送失败？没关系——第 5 步的扫描会兜底）

【到点了，延迟消息到达消费者】
  5. 查到计时单 → 检查"这件事做了没"：
     · 首响检查：工单已有 first_responded_at？→ 有：结算"按时"，结束
                                      → 没有：标记"超时"，触发升级！
     · 解决检查：工单已有 resolved_at？→ 有：结算"按时"，结束
                                    → 没有：标记"超时"，触发升级！

【升级 escalate()】
  6. 计时单标记 escalation_triggered=1（已升级，防止重复）
  7. 写一条审计日志（系统操作：SLA_ESCALATE）
  8. 发布内部事件 SlaTimeoutEvent
     → 状态机流转：transition(TIMEOUT_ESCALATE, SYSTEM)
     → 状态变 ESCALATED（仅当当前状态允许，见第 3 篇矩阵）

【每 5 分钟，补偿扫描兜底】
  9. 查计时单：escalation_triggered=0 AND 状态未结算 AND 截止时间已过
  10. 对每一条，走和第 5 步完全相同的检查逻辑
```

**记住两条"检查线"：延迟消息是准时的检查员，扫描是笨但可靠的检查员，它们查的是同一张表、走的是同一套逻辑。**

---

## 4.4 细节拆解一：为什么"消息丢失"不可怕（幂等设计）

面试官最爱问："**如果那条延迟消息丢了/重复了/迟到了，系统会怎样？**"

答案：都不会坏，因为每一步都有设计：

**消息丢了 → 扫描兜底**：最坏情况是晚 5 分钟升级（扫描周期），不会永远漏掉。因为扫描查询的条件是 `escalation_triggered=0 AND 未结算 AND 截止已过`——消息丢没丢，扫描不关心，只看事实。

**消息重复了 → 幂等忽略**：消费者第一步就查 `escalation_triggered`——已经升级过，直接忽略；已经结算"按时"过，也忽略。同一条消息投递 10 次，效果等于投递 1 次。

**消息迟到了 → 结算逻辑不依赖时间**：消费者不判断"现在几点了"，只看"这件事做没做"（有没有 first_responded_at）。迟到了就迟点结算，结论不变。

**延迟消息和扫描同时触发 → 抢着升级？** 不会。升级动作第一步检查 `escalation_triggered` 已置 1 则直接返回——**谁先到谁执行，后到的看见标记就退出**。审计日志也只有一条。

**这个设计的通用思想叫"幂等"**：同一个操作，做一次和做一百次，结果一样。实现幂等的两种手段：
1. **状态检查**：执行前查"是不是已经做过了"（escalation_triggered）。
2. **条件更新**：执行时用"状态作为 WHERE 条件"（第 5 篇的乐观锁）。

---

## 4.5 细节拆解二：结算规则（"按时"与"超时"怎么判）

结算对象是计时单的两个状态字段：first_response_status / resolve_status（0 未到期 / 1 按时 / 2 超时）。

| 时机 | 动作 |
|---|---|
| 客服第一次回复时 | transition(REPLY) 顺手写 first_responded_at（第 3 篇的同一条 UPDATE） |
| 首响延迟消息到达 | 有 first_responded_at → 结算 1（按时）；没有 → 结算 2（超时）并升级 |
| 客服点"解决"时 | transition(RESOLVE) 顺手写 resolved_at |
| 解决延迟消息到达 | 有 resolved_at → 结算 1；没有 → 结算 2 并升级 |

**一个重要规则：超时了不会翻盘**。假设首响时限是 2 小时，客服 3 小时后才回复——此时消息早已把状态结算为"超时"并升级。客服的回复不会把超时改回按时。**承诺的是"截止前响应"，晚了就是晚了**。这个规则保证了 SLA 统计的严肃性（按时率数据可信）。

**为什么结算依据是"事实字段"而不是"当前状态"？** 因为状态可能变来变去（处理中→等待客户→处理中），而 first_responded_at 一旦写入就不会被覆盖——时间戳是"不可变事实"，状态是"可变现状"。**结算永远基于不可变事实**，这是防止统计错乱的关键。

---

## 4.6 细节拆解三：补偿扫描为什么"笨"但可靠

```java
@Scheduled(cron = "${app.sla.compensation-cron}")   // 默认每 5 分钟
public void compensate() {
    // 扫描 1：首次响应超时未升级的
    List<TicketSlaDO> overdue = 查 ticket_sla WHERE
        escalation_triggered = 0 AND first_response_status = 0
        AND first_response_deadline < NOW();
    for (每条 : overdue) {
        handleDelayCheck(构造的消息);   // 复用和延迟消息完全相同的处理逻辑！
    }
    // 扫描 2：解决超时未升级的（同理）
}
```

三个设计点：
1. **查询条件 = 幂等保证**：只挑"没升级、没结算、已过截止"的。上次扫描处理过的（escalation_triggered 已置 1）永远不会再被选中——扫描跑多少次都安全。
2. **复用同一套处理逻辑**：handleDelayCheck 是延迟消息和扫描共用的入口——两条检查线逻辑不分裂，不会出现"消息处理了但扫描不认"的 bug。
3. **索引护航**：ticket_sla 上有 `idx_deadline_status(first_response_deadline, escalation_triggered)`——数据库索引和查询条件一一对应，扫描不会全表扫。

**为什么是 5 分钟不是 1 分钟？** 成本权衡：延迟消息正常工作时扫描只是兜底，5 分钟完全够；如果业务要更准时的兜底（比如紧急单 1 分钟内必须升级），把 cron 改密即可——配置化，改一行配置。

---

## 4.7 代码走读：核心类逐个看

### 4.7.1 生产者 SlaDelayProducer（投递消息）

```java
public void send(Long ticketId, Long slaId, String checkType, long deliverAtMs) {
    try {
        Message message = new Message(TOPIC, checkType,
                objectMapper.writeValueAsBytes(new SlaMessage(ticketId, slaId, checkType, null)));
        message.setDeliverTimeMs(deliverAtMs);          // 到点投递
        rocketMQTemplate.getProducer().send(message);
    } catch (Exception e) {
        log.error("SLA 延迟消息发送失败（将由补偿扫描兜底）", e);
        // 注意：不抛异常！发送失败不影响工单创建主流程
    }
}
```

消息体是个 record：`SlaMessage(ticketId, slaId, checkType, deadline)`——**关键设计：消费时查数据库拿一切事实，消息体只是"谁、查什么"的指引**。所以即使消息被篡改/重建，消费逻辑依然安全。

### 4.7.2 消费者 SlaDelayConsumer（收到消息）

```java
@RocketMQMessageListener(topic = "ticket-sla-check", consumerGroup = "ticket-sla-consumer")
public class SlaDelayConsumer implements RocketMQListener<String> {
    public void onMessage(String body) {
        // 1. JSON 反序列化成 SlaMessage
        // 2. 调 slaService.handleDelayCheck(message)  —— 真正的结算逻辑
        // 3. 异常：记 error 日志，不抛（交给补偿扫描兜底）
    }
}
```

**一个必须记住的细节**：监听器泛型必须写 `RocketMQListener<String>`——rocketmq-spring-boot-starter 默认按 String 转消息体，写成 byte[] 会 ClassCastException（这是项目实测踩过的坑）。

### 4.7.3 结算核心 handleDelayCheck（延迟消息和扫描共用）

```java
public void handleDelayCheck(SlaMessage message) {
    TicketSlaDO sla = 查计时单;
    if (sla == null) { return; }                       // 单子不存在（已删/回滚）→ 忽略
    if (sla.getEscalationTriggered() == 1) { return; } // 已升级 → 忽略（幂等！）

    switch (message.checkType()) {
        case "FIRST_RESPONSE":
            if (已响应) { 结算(status=1 按时); return; }
            sla.setFirstResponseStatus(2);             // 标超时
            updateById(sla);
            escalate(ticket, sla, "首次响应超时");      // 升级
        case "RESOLVE":
            // 同样的套路：已解决 → 按时；未解决 → 超时 + 升级
    }
}
```

### 4.7.4 升级 escalate（只执行一次）

```java
private void escalate(TicketDO ticket, TicketSlaDO sla, String reason) {
    if (sla.getEscalationTriggered() == 1) { return; }  // 双保险：再查一次
    sla.setEscalationTriggered(1);                      // 先置标记（幂等关键）
    sla.setEscalatedAt(now);
    updateById(sla);
    // 审计：action=SLA_ESCALATE，记录原因和工单号
    // 发布 SlaTimeoutEvent → 状态机流转 TIMEOUT_ESCALATE
}
```

**升级的防重是"先标记后动作"**：先置 escalation_triggered=1 再写审计、再发事件——任何一步重复触发，看到标记就直接退出。这比"先做事后标记"安全得多（做事失败时标记已经在，扫描不会再捡起来重复执行）。

### 4.7.5 状态机衔接（为什么用 Spring 事件）

升级要改变工单状态（→ ESCALATED），但 **SLA 服务不直接调工单服务**——通过 Spring 事件解耦：

```java
// SlaService 里：eventPublisher.publishEvent(new SlaTimeoutEvent(ticketId));
// TicketService 里：
@EventListener
@Transactional
public void onSlaTimeout(SlaTimeoutEvent event) {
    transition(event.ticketId(), TicketEvent.TIMEOUT_ESCALATE, null, "SYSTEM");
}
```

**好处**：两个模块互相不知道对方存在（不循环依赖）；如果未来升级动作多了（通知组长、发短信），只需加监听器，改 SLA 服务不动工单服务。

**边界情况**：如果工单当前状态不允许 TIMEOUT_ESCALATE（比如已解决）——transition 会抛 ILLEGAL_TRANSITION，被全局异常处理吞掉。**状态机是最后一道防线：即使 SLA 逻辑有 bug 想升级一张已解决的单，状态机也会拒绝**。

---

## 4.8 "先落库后发消息"原则

看 createTicketSla 的顺序：

```
1. INSERT ticket_sla（先落库）
2. 投延迟消息（后发消息）
```

**为什么顺序不能反？**
- 先发消息后落库：消息 2 小时后到达，消费时查计时单——**查不到**（如果事务还没提交，或者更糟：消息发出后事务回滚了，计时单根本没建）。结算逻辑只能忽略，这条工单从此没有 SLA 保障。
- 先落库后发消息：即使消息丢了，扫描按数据库事实兜底，**SLA 永远不会丢**。

推广一下：**凡是"发消息"这种有外部副作用的操作，都要"先落本地数据，再对外通知"**——数据是事实源，消息只是提醒。

---

## 4.9 常见疑问速答

**Q：SLA 策略没配置怎么办？**
A：创建计时单时按优先级查不到启用策略 → log.warn 跳过计时——工单照常流转，只是没有 SLA 保障（降级容忍，不因配置缺失卡死业务）。

**Q：为什么 ticket 主表和 ticket_sla 表都有截止时间？**
A：主表冗余一份是为了列表页/详情页展示免联表；计时单是"结算依据"。两处时间在创建时同源写入，改动点收敛（都在 SLA 创建/结算时）。

**Q：消息消费失败重试多少次？**
A：RocketMQ 默认 16 次退避重试；重试耗尽还失败，消费者 catch 记日志不抛——交给扫描兜底。**消息系统不承诺"一定成功"，承诺"失败的痕迹可以被发现和补救"**。

**Q：升级动作（escalate_action）是干什么的？**
A：策略上可配 JSON（如 {"notifyGroupId":1} 通知某技能组），当前实现落审计日志 + 站内信占位——动作本身配置化，具体实现是扩展点。

**Q：用定时任务每 1 分钟扫一次行不行，不用延迟消息行不行？**
A：行，但代价是"全量扫描的周期"就是"结算精度"——1 分钟扫一次意味着升级误差最多 1 分钟，且每次都全表扫，数据量大了是持续开销。延迟消息按单触发、到点即查，精度毫秒级、开销 O(1)。**两者不是替代关系，是配合关系**。

---

## 4.10 本篇小结

- SLA = 服务承诺（首响时限 + 解决时限），超时自动升级。
- 双保险：**延迟消息保证准时，补偿扫描保证不漏**——查同一张表、走同一套逻辑。
- 幂等三件套：消费前查 escalation_triggered、升级先标记后动作、扫描条件只选"未处理"。
- 结算基于不可变事实（时间戳），超时不翻盘。
- 先落库后发消息；发送失败不抛异常；一切交给"数据 + 扫描"兜底。
- 面试一句话：**"消息可能丢，数据不会丢；准时靠消息，最终靠扫描。"**

**下一篇**：《并发控制与分派从零拆解》——两个客服同时抢一张单怎么办？乐观锁、分布式锁、四种分派策略。

---

*继续学习：第 5 篇 · 并发控制与分派从零拆解*
