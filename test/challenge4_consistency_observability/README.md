# 挑战四：一致性与可观测性测试设计

## 核心机制（对应 PPT 挑战四）

```
状态一致                              可观测性
────────────────────────────────      ─────────────────────────────────
Redis 意图树缓存                       RagTrace 全链路追踪
(IntentTreeCacheManager)              (RagTraceContext + RagTraceAspect)

Redisson 分布式锁                     Snowflake 分布式 ID
(JdbcConversationMemorySummaryService) (SnowflakeIdInitializer + CustomIdentifierGenerator)
```

> 可靠性既来自机制本身，也来自"能看见、能追、能复盘"。

## 测试文件

| 文件 | 类型 | 目标 |
|------|------|------|
| `IntentTreeCacheManagerTest.java` | 单元测试（Mockito） | 验证 Redis 意图树缓存的命中/缺失/TTL/清除/防御性降级 |
| `RagTraceContextTest.java` | 单元测试（纯 Java） | 验证 TransmittableThreadLocal 链路上下文的读写/栈管理/清理 |

## 测试用例矩阵

### IntentTreeCacheManagerTest（7 个用例）

| ID | 名称 | 验证点 |
|----|------|--------|
| TC01 | 缓存未命中 | getIntentTreeFromCache 返回 null，触发 DB 回退 |
| TC02 | 缓存命中 | JSON 正确反序列化为 IntentNode 列表 |
| TC03 | saveIntentTreeToCache | set(key, json, 7L, DAYS) 写入正确 TTL |
| TC04 | clearIntentTreeCache（key 存在） | 正常删除，调用方无异常 |
| TC05 | clearIntentTreeCache（key 不存在） | 静默忽略，幂等清除 |
| TC06 | isCacheExists | 正确反映 Redis hasKey 结果 |
| TC07 | Redis 连接异常 | 防御性返回 null，不上抛，链路不中断 |

### RagTraceContextTest（7 个用例）

| ID | 名称 | 验证点 |
|----|------|--------|
| TC01 | 初始状态 | traceId=null, depth=0, currentNodeId=null |
| TC02 | setTraceId / getTraceId | 读写闭环，值完全一致 |
| TC03 | pushNode 一次 | depth=1, currentNodeId 正确 |
| TC04 | pushNode 两次 | LIFO：currentNodeId = 最新压入 |
| TC05 | popNode | depth 递减，currentNodeId 回退到父节点 |
| TC06 | clear() | 全部 ThreadLocal 清零，防线程池泄漏 |
| TC07 | 五阶段 RAG 流水线 | query-rewrite→intent-classify→retrieve→rerank→stream-output 栈管理正确 |

## 运行方式

```bash
# 1. 将测试文件移动到对应 Maven 模块的测试目录
cp test/challenge4_consistency_observability/IntentTreeCacheManagerTest.java \
   bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/intent/

cp test/challenge4_consistency_observability/RagTraceContextTest.java \
   bootstrap/src/test/java/com/nageoffer/ai/ragent/framework/trace/

# 2. 运行 Redis 缓存测试
mvn test -pl bootstrap \
  -Dtest=IntentTreeCacheManagerTest -DfailIfNoTests=false

# 3. 运行全链路追踪上下文测试
mvn test -pl bootstrap \
  -Dtest=RagTraceContextTest -DfailIfNoTests=false

# 4. 运行全部挑战四测试并生成 HTML 报告
bash test/challenge4_consistency_observability/run.sh IntentTreeCacheManagerTest
bash test/challenge4_consistency_observability/run.sh RagTraceContextTest
```

## 关键设计决策

- **TC07 防御性 null 返回**：`getIntentTreeFromCache` 捕获所有异常返回 null，调用方（`DefaultIntentClassifier`）检测到 null 后回退到 DB 加载，保证 Redis 故障时 RAG 链路不完全中断。

- **TC07 五阶段栈验证**：PPT 明确列出 5 个 RAG 阶段。TC07 同时验证同层节点（顺序 push/pop）和嵌套节点（rerank 在 retrieve 内部），覆盖 `RagTraceAspect` 的两种 `parentNodeId` 场景。

- **clear() @BeforeEach + @AfterEach**：`RagTraceContext` 使用 `TransmittableThreadLocal`，测试线程与生产线程池线程复用同一 Thread，必须在每个 case 前后清理，否则用例间状态相互污染。

- **Redisson 分布式锁不单独测试**：`JdbcConversationMemorySummaryService.doCompressIfNeeded()` 依赖 LLMService、ConversationGroupService 等多个 Spring Bean，隔离成本高；锁语义（`tryLock → false = 跳过`）通过集成测试覆盖更合适。

- **Snowflake 不单独测试**：`SnowflakeIdInitializer` 依赖 Lua 脚本 + Redis（与 Challenge 1 的 `LuaAtomicClaimTest` 类似），可用 Testcontainers 扩展；`CustomIdentifierGenerator` 仅是对 `IdUtil.getSnowflakeNextId()` 的简单代理，测试价值在并发唯一性上，已属集成测试范畴。
