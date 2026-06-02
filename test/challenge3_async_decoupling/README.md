# 挑战三：异步解耦测试设计

## 核心机制（对应 PPT 挑战三）

```
sendInTransaction()
  ├── 1. Half 消息发送到 Broker（消息暂存，不投递）
  ├── 2. 执行本地事务（DB 更新 status=RUNNING）
  │        成功 → COMMIT  → Broker 投递消息给消费者
  │        失败 → ROLLBACK → Broker 丢弃 Half 消息
  └── 3. Broker 回查（TransactionChecker）
           查 DB status == RUNNING → true  → COMMIT
           其他                   → false → ROLLBACK
```

核心组件：
- `DelegatingTransactionListener`：路由总线，按 txId 执行本地事务，按 topic 路由回查
- `KnowledgeDocumentChunkTransactionChecker`：DB 回查，status=RUNNING 即为已提交
- txId 一次性消费（`ConcurrentHashMap.remove`），防重放幂等
- 回查走 DB，不依赖内存，支持多实例水平部署

## 测试文件

| 文件 | 类型 | 目标 |
|------|------|------|
| `DelegatingTransactionListenerTest.java` | 单元测试（Mockito @InjectMocks） | 验证事务状态机全分支：COMMIT/ROLLBACK 决策、txId 幂等清理、checker 路由 |
| `KnowledgeDocumentChunkTransactionCheckerTest.java` | 单元测试（Mockito mock DB） | 验证 DB 回查逻辑：RUNNING→true，其他状态/null→false，端到端路由链路 |

## 测试用例矩阵

### DelegatingTransactionListenerTest（8 个用例）

| ID | 名称 | 验证点 |
|----|------|--------|
| TC01 | 本地事务成功 | executeLocalTransaction 返回 COMMIT，consumer 调用一次 |
| TC02 | 本地事务抛异常 | 返回 ROLLBACK，消息丢弃 |
| TC03 | HEADER_TX_ID 缺失 | 返回 ROLLBACK（防御性处理） |
| TC04 | txId 未注册 | 返回 ROLLBACK |
| TC05 | txId 一次性消费 | 首次 COMMIT，第二次 ROLLBACK（防重放） |
| TC06 | 回查 checker=true | checkLocalTransaction 返回 COMMIT |
| TC07 | 回查 checker=false | checkLocalTransaction 返回 ROLLBACK |
| TC08 | 回查无 checker | 返回 ROLLBACK（保守兜底） |

### KnowledgeDocumentChunkTransactionCheckerTest（5 个用例）

| ID | 名称 | 验证点 |
|----|------|--------|
| TC-C01 | 状态 RUNNING | check 返回 true，触发 COMMIT |
| TC-C02 | 状态 SUCCESS | check 返回 false |
| TC-C03 | 状态 PENDING | check 返回 false，本地事务未提交 |
| TC-C04 | 文档不存在 | check 返回 false，null 安全 |
| TC-C05 | 端到端路由 | registerChecker → HEADER_TOPIC → checkLocalTransaction 返回 COMMIT |

## 运行方式

```bash
# 1. 将测试文件移动到对应 Maven 模块的测试目录
cp test/challenge3_async_decoupling/DelegatingTransactionListenerTest.java \
   bootstrap/src/test/java/com/nageoffer/ai/ragent/framework/mq/producer/

cp test/challenge3_async_decoupling/KnowledgeDocumentChunkTransactionCheckerTest.java \
   bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/mq/

# 2. 运行事务状态机测试
mvn test -pl bootstrap \
  -Dtest=DelegatingTransactionListenerTest -DfailIfNoTests=false

# 3. 运行 DB 回查测试
mvn test -pl bootstrap \
  -Dtest=KnowledgeDocumentChunkTransactionCheckerTest -DfailIfNoTests=false

# 4. 运行全部挑战三测试并生成 HTML 报告
bash test/challenge3_async_decoupling/run.sh DelegatingTransactionListenerTest
bash test/challenge3_async_decoupling/run.sh KnowledgeDocumentChunkTransactionCheckerTest
```

## 关键设计决策

- **TC05 txId 防重放验证**：PPT 原文"txId 一次性消费"。`ConcurrentHashMap.remove()` 确保并发安全，执行后 txId 消失，第二次相同消息返回 ROLLBACK。

- **TC-C01 仅 RUNNING 返回 true**：只有 `status="running"` 才证明本地事务（`status=RUNNING` 更新）已提交。PENDING/SUCCESS/FAILED 均保守返回 false。

- **TC-C05 无需 txManager**：`checkLocalTransaction` 路径不调用 `TransactionTemplate`，可用无参构造直接实例化 `DelegatingTransactionListener`，验证纯路由逻辑。

- **@InjectMocks 注入 txManager**：`DelegatingTransactionListener` 使用 `@Autowired` 字段注入，Mockito `@InjectMocks` 通过字段匹配自动注入 mock，无需 Spring 容器。

- **包私有常量访问**：`HEADER_TX_ID` 和 `HEADER_TOPIC` 无 `public` 修饰；`DelegatingTransactionListenerTest` 与被测类同包（`com.nageoffer.ai.ragent.framework.mq.producer`）才可直接访问。
