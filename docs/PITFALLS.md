# TicketAI 踩坑与解决记录（面试素材）

> 开发过程中真实遇到的问题与解决过程。每个坑按「现象 → 排查 → 根因 → 解决 → 面试价值」组织。
> 面试讲坑的方法论：不要说"遇到了一个 bug 后来好了"，要讲清四件事——**报错现象是什么、怎么定位的、根本原因是什么、怎么修复和预防的**。定位过程比结论更体现能力。

## 索引（⭐ = 面试高频，建议重点准备）

| 类别 | 坑 |
|---|---|
| 框架与依赖 | ⭐1 JDK23+Lombok 失效 · 2 MP 分页插件拆模块 · ⭐3 MP @Version 缺陷 · 4 LambdaUpdateWrapper 单测不可用 · 5 BaseMapper 重载 Mockito 歧义 · 6 ES 8.14 客户端 API |
| 中间件 | ⭐7 Redis 旧版 expire 栈溢出 · ⭐8 RocketMQ 定时消息默认关闭 · 9 MQ 监听器泛型 · 10 容器卷权限 |
| 业务设计 | ⭐11 状态机矩阵遗漏 · ⭐12 事务内异步数据可见性 · 13 循环依赖事件解耦 · 14 @Async 代理限制 · 15 Spring Map 注入 key 不匹配 · ⭐16 空字符串参数当筛选条件 |
| 环境工具 | 17 JDBC 编码参数 · 18 curl 中文 GBK · 19 MSYS 路径转换 · 20 端口占用与旧进程残留 |

---

## 一、框架与依赖坑

### 1. JDK 23 下 Lombok 静默失效 ⭐

- **现象**：编译报大量"找不到符号：方法 setCode(int)"，所有 @Getter/@Slf4j 生成的方法全部缺失，但 Lombok 依赖明明在 pom 里。
- **排查**：先怀疑依赖没引入 → `mvn dependency:tree` 确认 lombok 1.18.46 在；再手工 `javac -cp lombok.jar` 编译测试类，发现同样不生成方法 → 排除 Maven 配置，锁定 JDK 行为。
- **根因**：JDK 21 引入 `-proc` 参数后，**JDK 23 起 javac 默认 `-proc:none`，不再自动运行 classpath 上的注解处理器**，Lombok 被静默跳过。
- **解决**：maven-compiler-plugin 显式配置 `<proc>full</proc>` + `annotationProcessorPaths` 声明 Lombok。
- **面试价值**：体现"报错在下游、根因在上游"的排查思路——错误指向 getter 不存在，真相是注解处理器没跑。

### 2. MyBatis-Plus 3.5.9+ 分页插件拆分独立模块

- **现象**：import `PaginationInnerInterceptor` 报找不到符号。
- **根因**：MP 3.5.9 起分页插件（依赖 jsqlparser 解析 COUNT 语句）从主包拆到 `mybatis-plus-jsqlparser` 模块。
- **解决**：补充同版本 `mybatis-plus-jsqlparser` 依赖。
- **面试价值**：依赖升级的破坏性变更意识——升级框架小版本前看 breaking changes。

### 3. MyBatis-Plus @Version 拦截器缺陷（version=0）⭐

- **现象**：并发更新时抛 `BindingException: Parameter 'MP_OPTLOCK_VERSION_ORIGINAL' not found`，且异常发生在补偿扫描线程，主流程（version=null 的 insert 后回填）却正常，极难稳定复现。
- **排查**：异常被框架吞掉只留 NPE 堆栈 → 对比正常/失败路径的唯一差异（实体 version 值）→ 锁定 @Version 拦截器在 version=0 时的参数注入缺陷。
- **根因**：MP 3.5.17 乐观锁拦截器对 version=0 实体生成 `WHERE version=#{MP_OPTLOCK_VERSION_ORIGINAL}` 却未注入对应参数。
- **解决**：状态变更改用字符串 `UpdateWrapper` 显式拼接乐观锁条件（`WHERE version=? AND status=?` + `SET version=version+1`），语义与 @Version 完全一致，SQL 可审计；同时发现 LambdaUpdateWrapper 依赖 MP 运行时缓存、纯单测环境不可用，一并规避。
- **面试价值**：最硬核的一个——"框架文档说支持但实测有缺陷"的完整案例，含报错信息、定位路径、绕过方案，还能引申"为什么显式 SQL 比魔法注解可控"。

### 4. LambdaUpdateWrapper 在纯单测环境不可用

- **现象**：单元测试里构造 LambdaUpdateWrapper 抛 `can not find lambda cache for this entity`。
- **根因**：lambda 解析依赖 MyBatis-Plus 启动时初始化的 TableInfo 缓存，Mockito 纯单测没有该运行时。
- **解决**：状态变更统一改字符串列名 UpdateWrapper（列名与锁定版 DDL 对应，风险可控）。
- **面试价值**：测试环境与生产环境行为一致性意识。

### 5. MP BaseMapper 批量重载导致 Mockito 歧义

- **现象**：`verify(mapper).insert(any())` 编译报"引用不明确"。
- **根因**：MP 3.5.17 BaseMapper 新增 `insert(Collection)` 批量重载，裸 `any()` 无法推断重载。
- **解决**：Mockito 匹配器显式类型 `any(XxxDO.class)`，基本类型参数用 `anyLong()`。
- **面试价值**：小坑，体现对"泛型推断 + 方法重载"交互的理解。

### 6. Elasticsearch 8.14 Java 客户端 API 适配

- **现象**：三个连环编译错误——SearchRequest 不带泛型参数、knn 不在 bool query 变体里、客户端方法抛 checked IOException。
- **解决**：knn 是 SearchRequest **顶层参数**（与 query 并列，ES 自动合并分数）；SearchResponse 需强转；方法签名声明 IOException 由调用方统一降级。
- **面试价值**：新客户端版本 API 迁移能力；顺带能讲"检索接口设计"——向量检索不可用时自动降级纯全文。

---

## 二、中间件坑

### 7. Redis 旧版本 + spring-data-redis 的 expire 递归栈溢出 ⭐

- **现象**：调用 `stringRedisTemplate.expire(key, Duration)` 直接 `StackOverflowError`，堆栈全是 `DefaultedRedisConnection.pExpire` 自递归。
- **根因**：spring-data-redis 3.5 的接口默认方法（expire ↔ pExpire）互调，在老版本 Redis（本机 3.0.504 Windows 移植版）路径上未走实现类重写，形成无限递归。
- **解决**：编号生成器改用 Lua 脚本一步完成 INCR + EXPIRE（顺带比两次调用更原子）。
- **面试价值**：能讲出"接口默认方法互调 + 实现未覆盖"这种栈溢出形态；顺带引出"为什么编号生成用 Lua"（原子性 + 绕开客户端封装）。

### 8. RocketMQ 定时消息默认关闭 ⭐

- **现象**：SLA 延迟消息 `producer.send` 返回成功，但到点消费者收不到消息，broker 的 store 里也没有 delayOffset 进度文件；只有补偿扫描在兜底升级。
- **排查**：对比"消息发送成功日志"与"消费日志缺失" → 检查 broker store 目录发现定时消息进度文件不存在 → 锁定 broker 端定时消息服务未启用。
- **根因**：RocketMQ 5.x broker 的 `enableScheduleMessage` **默认 false**，任意延迟定时消息（时间轮）需要显式开启。
- **解决**：broker.conf 加 `enableScheduleMessage=true` 重启；重测 1 分钟策略精确到秒触发。
- **面试价值**：SLA 引擎的核心坑——"生产者发送成功 ≠ 消息会被定时投递"；也是"补偿扫描兜底设计"为什么必要的实证（排查期间全靠补偿扫描在升级）。

### 9. RocketMQ 监听器泛型必须 String

- **现象**：消费时报 `ClassCastException: String cannot be cast to byte[]`，消息反复重试。
- **根因**：rocketmq-spring-boot-starter 按监听器泛型做消息体转换，`RocketMQListener<byte[]>` 实际收到的是 String。
- **解决**：监听器泛型改 `RocketMQListener<String>`，手动 JSON 反序列化。
- **面试价值**：starter 封装层的隐式约定；顺带讲消费失败重试 16 次后进死信、由补偿扫描兜底的完整链路。

### 10. 容器命名卷权限

- **现象**：RocketMQ broker 容器反复重启，日志只有关闭时的 NPE，真实异常被吞。
- **根因**：官方镜像默认以 rocketmq 用户（uid 3000）运行，Docker 命名卷属 root 且 755，store 目录不可写导致初始化失败。
- **解决**：开发环境 compose 加 `user: "0"`。
- **面试价值**：容器化排障——"应用日志只有次生异常时，去查宿主/卷的权限与属主"。

---

## 三、业务设计坑

### 11. 状态机矩阵设计遗漏 ⭐

- **现象**：SLA 补偿扫描触发升级时报"当前状态[待分派]不允许事件[SLA超时升级]"——状态机把合法业务拒绝了。
- **根因**：设计矩阵时 TIMEOUT_ESCALATE 只配在"处理中/等待客户"，**遗漏了"待分派"**——但"建单后没人分派导致超时"恰恰是最该升级的场景。设计评审时没发现，是真实业务跑出来才暴露。
- **解决**：矩阵补充 `PENDING_ASSIGN --TIMEOUT_ESCALATE--> ESCALATED`（22 → 23 条），同步更新测试与文档。
- **面试价值**：最好的"设计缺陷被运行时暴露"案例——说明为什么状态机要做全量测试，以及"设计文档 + 代码 + 测试三处同步"的重要性。

### 12. 事务内触发异步任务的数据可见性 ⭐

- **现象**：工单创建成功，但异步自动分派偶发查不到这张新工单。
- **根因**：create() 事务尚未提交时就发布了事件，@Async 监听线程在另一个连接里读不到未提交数据（RR 隔离级别）。
- **解决**：`TransactionSynchronizationManager.registerSynchronization` 注册 **afterCommit** 再发布事件——保证分派执行时数据已落库；无事务环境（单测）降级为直接发布。
- **面试价值**：高频考点——"事务 + 异步/消息的一致性"，能引申到"先落库后发消息""事务消息""本地消息表"的完整谱系。

### 13. 服务循环依赖 → 事件解耦

- **现象**：SLA 升级需要调工单状态机（SlaService → TicketService），而建单又要启动 SLA 计时（TicketService → SlaService），构造器注入循环依赖启动失败。
- **解决**：SlaService 发 `SlaTimeoutEvent`，TicketService @EventListener 消费后走状态机——单向依赖 + 事件解耦。同样的模式复用在"建单 → 自动分派"（TicketCreatedEvent）。
- **面试价值**：Spring 循环依赖的标准解法对比（@Lazy / setter / 事件 / 拆模块），以及"领域事件"在单体里的落地。

### 14. @Async + @EventListener 的 JDK 代理限制

- **现象**：启动报 `Need to invoke method 'onTicketCreated' declared on target class, but not found in any interface(s)`。
- **根因**：@EnableAsync 默认对有接口的类用 JDK 动态代理，@EventListener 处理器要求方法在接口上可见。
- **解决**：事件监听方法声明提升到 DispatchService 接口。
- **面试价值**：Spring AOP 两种代理机制的差异（JDK vs CGLIB）落到真实报错。

### 15. Spring Map 注入的 key 是 bean 名

- **现象**：分派工厂日志"未注册的分派策略: ROUND_ROBIN"——配置表编码与注入 Map 的 key 对不上。
- **根因**：Spring 注入 `Map<String, DispatchStrategy>` 的 key 是 **bean 名（小驼峰类名）**，不是策略编码。
- **解决**：改为注入 `List<DispatchStrategy>`，构造时按 `type()` 建映射。
- **面试价值**：Spring 依赖注入的冷知识，一行代码的坑但能讲清注入原理。

### 16. 前端空字符串参数被当筛选条件 ⭐

- **现象**：数据库 113 张工单，页面列表永远"暂无数据"；直接 curl 不带参数却有数据。
- **排查**：对比两者请求差异 → 前端把空字符串 `category=` 原样发给后端 → 后端 `eq(category != null, category)` 判断放过了 null 却没防住空字符串 → 生成了 `category = ''` 条件，把所有有分类的工单全过滤掉。
- **解决**：后端筛选条件统一加 isBlank 判断（防御），前端发送前过滤空值参数（根治）。
- **面试价值**：前后端联调最经典的双侧缺陷——修复后顺带把"参数校验应该在后端做最终防御"讲出来。

---

## 四、环境与工具坑

### 17. JDBC URL 编码参数

- **现象**：应用启动报 `CannotGetJdbcConnectionException`，但 mysql 命令行能连。
- **根因**：URL 里 `characterEncoding=utf8mb4` 不被 Connector/J 接受（必须写 `UTF-8`，驱动自动映射 utf8mb4）。
- **面试价值**：JDBC URL 参数语义；顺带讲 utf8mb4 与 utf8 的区别。

### 18. Git Bash 下 curl 中文变 GBK

- **现象**：命令行带中文的 JSON 请求，服务端 Jackson 报 `Illegal unquoted character` 解析 500。
- **根因**：Git Bash 把命令行中文按 GBK 编码传出，服务端按 UTF-8 解析失败。
- **解决**：测试 JSON 一律写 UTF-8 文件用 `--data-binary @file` 发送。
- **面试价值**：小坑，但"编码问题在传输层而非业务层"的定位思路通用。

### 19. MSYS 路径转换破坏 docker -v

- **现象**：`docker run -v /home/rocketmq/conf/broker.conf:...` 报 `NoSuchFileException: C:/Program`。
- **根因**：Git Bash 的 MSYS 路径转换把卷参数里的 Unix 路径改写成了 Git 安装路径。
- **解决**：`MSYS_NO_PATHCONV=1` 或 compose 相对路径。
- **面试价值**：Windows 下容器开发的典型环境陷阱。

### 20. 端口占用与旧进程残留

- **现象 A**：启动报 Port 8080 already in use → 应用固定改 8090。
- **现象 B**：改完代码重启验证，接口行为还是旧的——`pkill -f` 没杀干净 java 子进程，新实例因端口占用启动失败，请求打到旧进程。
- **解决**：先 `netstat -ano` 找监听 PID 再 `taskkill //F //PID`，确认端口释放后再启动。
- **面试价值**："验证结果与预期不符时，先确认你连的是不是你以为的那个进程"——最朴素也最容易忽略的排查第一步。

---

## 附：怎么在面试里讲这些坑（模板）

1. **现象**：先说报错/异常行为（具体到错误信息）；
2. **定位**：怎么缩小范围的（对比正常路径、查日志线程、最小复现）；
3. **根因**：一句话说透本质（版本缺陷/默认配置/框架机制）；
4. **解决**：改了什么，为什么这样改而不是那样改；
5. **预防**：加测试/写进文档/统一规范。

推荐重点准备的五个：**#3 @Version 缺陷**（框架源码级）、**#8 定时消息默认关闭**（中间件配置 + 兜底设计实证）、**#11 状态机遗漏**（设计评审价值）、**#12 事务可见性**（高频考点）、**#16 空参数筛选**（联调经典）。每个都能讲 2-3 分钟，且全部有真实日志/测试可佐证。
