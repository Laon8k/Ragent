# 挑战一：高并发测试设计

## 核心机制（对应 PPT 挑战一）

```
请求入队 → 原子 claim → 信号量控并发 → SSE 实时反馈
(ZSet)      (Lua)        (Redisson)      (排队/执行/拒绝)
```

## 测试文件

| 文件 | 类型 | 目标 |
|------|------|------|
| `ChatQueueLimiterTest.java` | 单元测试（Mockito） | 验证 ChatQueueLimiter 各分支逻辑 |
| `LuaAtomicClaimTest.java` | 集成测试（Testcontainers Redis） | 验证 Lua 脚本原子性与公平排队 |

## 测试用例矩阵

### ChatQueueLimiterTest（8 个用例）

| ID | 名称 | 验证点 |
|----|------|--------|
| TC01 | 限流关闭时直通 | `globalEnabled=false` → 直接执行，不操作 Redis |
| TC02 | 有许可立即执行 | 信号量有剩余 + Lua claim 成功 → `onAcquire` 立即运行 |
| TC03 | 无许可进入排队 | `availablePermits=0` → `zadd` 写入 ZSet，score 递增 |
| TC04 | 超时触发有序 SSE | 超过 `maxWaitSeconds` → META → REJECT → FINISH → DONE |
| TC05 | 拒绝先于落库 | SSE `complete()` 在 `memoryService.append()` 之前 |
| TC06 | Emitter 完成释放许可 | `emitter.complete()` → `semaphore.release()` + `topic.publish()` |
| TC07 | 非队头 claim 失败 | Lua 返回 `{0}` → 信号量未消耗，`onAcquire` 未执行 |
| TC08 | 并发数上限受控 | 20 并发请求，峰值活跃数 ≤ `globalMaxConcurrent(3)` |

### LuaAtomicClaimTest（5 个用例）

| ID | 名称 | 验证点 |
|----|------|--------|
| TC-L01 | requestId 不在队列 | 返回 `{0}` |
| TC-L02 | rank ≥ maxRank | 返回 `{0}`，requestId 不被移除 |
| TC-L03 | 队头 claim | 返回 `{1, score}`，从 ZSet 移除 |
| TC-L04 | 10 并发 claim 同一 ID | 只有 1 次成功（Lua 原子性） |
| TC-L05 | FIFO 顺序推进 | 按 score 从小到大依次 claim 成功 |

## 运行方式

```bash
# 1. 将文件移动到 Maven 模块
cp ChatQueueLimiterTest.java \
   ../../bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/aop/

cp LuaAtomicClaimTest.java \
   ../../bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/aop/

# 2. 运行单元测试（不需要 Docker）
cd ../../ && mvn test -pl bootstrap \
  -Dtest=ChatQueueLimiterTest -DfailIfNoTests=false

# 3. 运行集成测试（需要 Docker，Testcontainers 自动拉起 Redis）
mvn test -pl bootstrap \
  -Dtest=LuaAtomicClaimTest -DfailIfNoTests=false
```

## 关键设计决策

- **TC05 优先返回验证**：PPT 原文"拒绝事件优先返回，落库异步化，避免高并发下拒绝延迟被数据库拖慢"。测试通过调用顺序断言（`sse_complete` 先于 `db_persist`）验证这一点。
- **TC08 使用真实 Semaphore**：用 `java.util.concurrent.Semaphore` 替换 Redisson mock，使并发数约束可真实触发，避免 mock 掩盖竞态问题。
- **TC-L04 原子性**：10 个线程同时对同一 `requestId` 执行 `EVAL`，验证 Redis 单线程执行 Lua 的原子性保证——这正是系统选择 Lua 而非多步命令的原因。
