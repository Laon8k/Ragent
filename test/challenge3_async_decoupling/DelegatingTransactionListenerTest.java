/*
 * 挑战三：异步解耦 — DelegatingTransactionListener 事务消息状态机单元测试
 *
 * 放置路径（运行前请移动到）：
 *   bootstrap/src/test/java/com/nageoffer/ai/ragent/framework/mq/producer/DelegatingTransactionListenerTest.java
 *
 * 注意：HEADER_TX_ID / HEADER_TOPIC 是 package-private 常量，测试必须与 DelegatingTransactionListener 同包。
 *
 * 依赖（已在父 pom 中）：
 *   junit-jupiter, mockito-core, spring-tx（spring-boot-starter 已涵盖）
 *
 * 测试矩阵：
 *   TC01  本地事务成功 → executeLocalTransaction 返回 COMMIT
 *   TC02  本地事务抛异常 → executeLocalTransaction 返回 ROLLBACK
 *   TC03  HEADER_TX_ID 缺失（null）→ executeLocalTransaction 返回 ROLLBACK
 *   TC04  txId 存在但未注册到 map → executeLocalTransaction 返回 ROLLBACK
 *   TC05  成功执行后 txId 从 map 移除（防重放）→ 第二次调用返回 ROLLBACK
 *   TC06  回查 checker 返回 true → checkLocalTransaction 返回 COMMIT
 *   TC07  回查 checker 返回 false → checkLocalTransaction 返回 ROLLBACK
 *   TC08  回查 topic 无对应 checker → checkLocalTransaction 返回 ROLLBACK
 *
 * 核心设计验证（对应 PPT 挑战三）：
 *   - Half 消息落地后执行本地事务，成功→COMMIT（消息可投递），失败→ROLLBACK（消息丢弃）
 *   - Broker 回查走 DB（TransactionChecker），不依赖内存状态，支持多实例部署
 *   - txId 一次性消费后清理，保证幂等，不会被重复执行
 */

package com.nageoffer.ai.ragent.framework.mq.producer;

import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("挑战三：异步解耦 — DelegatingTransactionListener 事务消息状态机")
class DelegatingTransactionListenerTest {

    @InjectMocks
    private DelegatingTransactionListener listener;

    @Mock
    private PlatformTransactionManager txManager;

    @BeforeEach
    void setUp() {
        // TransactionTemplate 需要 getTransaction() 返回非 null 的 TransactionStatus
        TransactionStatus mockStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(mockStatus);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC01  本地事务成功 → COMMIT
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC01 本地事务执行成功：executeLocalTransaction 返回 COMMIT，consumer 被调用一次")
    void tc01_localTransactionSucceeds_returnsCommit() {
        String txId = "tx-001";
        AtomicBoolean executed = new AtomicBoolean(false);
        listener.registerLocalTransaction(txId, arg -> executed.set(true));

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(
                buildTxMessage(txId), null);

        assertThat(state)
                .as("本地事务成功应返回 COMMIT，触发消息投递")
                .isEqualTo(RocketMQLocalTransactionState.COMMIT);
        assertThat(executed.get())
                .as("本地事务 consumer 应被执行一次")
                .isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC02  本地事务抛异常 → ROLLBACK
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC02 本地事务抛 RuntimeException：executeLocalTransaction 返回 ROLLBACK，消息丢弃")
    void tc02_localTransactionThrows_returnsRollback() {
        String txId = "tx-002";
        listener.registerLocalTransaction(txId,
                arg -> { throw new RuntimeException("DB 写入失败"); });

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(
                buildTxMessage(txId), null);

        assertThat(state)
                .as("本地事务失败应返回 ROLLBACK，消息丢弃保证一致性")
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC03  HEADER_TX_ID 缺失（null）→ ROLLBACK
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC03 消息头中无 HEADER_TX_ID：executeLocalTransaction 返回 ROLLBACK（防御性处理）")
    void tc03_missingTxIdHeader_returnsRollback() {
        // 构造不含 HEADER_TX_ID 的消息
        Message<?> msg = MessageBuilder
                .withPayload(MessageWrapper.builder().keys("k").body("payload").build())
                .build();

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(msg, null);

        assertThat(state)
                .as("缺少 txId 头时应返回 ROLLBACK")
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC04  txId 存在但未注册到 map → ROLLBACK
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC04 txId 在消息头中存在但未注册到 localTransactionMap：返回 ROLLBACK")
    void tc04_txIdNotRegistered_returnsRollback() {
        listener.registerLocalTransaction("registered-tx", arg -> {});

        // 使用未注册的 txId 调用
        RocketMQLocalTransactionState state = listener.executeLocalTransaction(
                buildTxMessage("unknown-tx-999"), null);

        assertThat(state)
                .as("未注册的 txId 应返回 ROLLBACK")
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC05  执行后 txId 从 map 移除，防止重放执行
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC05 txId 一次性消费：执行成功后从 map 移除，第二次调用返回 ROLLBACK（防重放）")
    void tc05_afterExecution_txIdRemovedPreventingReplay() {
        String txId = "tx-005";
        AtomicInteger callCount = new AtomicInteger(0);
        listener.registerLocalTransaction(txId, arg -> callCount.incrementAndGet());

        Message<?> msg = buildTxMessage(txId);

        RocketMQLocalTransactionState first  = listener.executeLocalTransaction(msg, null);
        RocketMQLocalTransactionState second = listener.executeLocalTransaction(msg, null);

        assertThat(first).as("首次执行应返回 COMMIT").isEqualTo(RocketMQLocalTransactionState.COMMIT);
        assertThat(callCount.get()).as("consumer 只应被调用 1 次").isEqualTo(1);
        assertThat(second).as("txId 已移除，第二次调用返回 ROLLBACK（防重放）")
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC06  回查 checker 返回 true → COMMIT
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC06 回查：checker.check() 返回 true（DB 状态已提交）→ checkLocalTransaction 返回 COMMIT")
    void tc06_checkerReturnsTrue_checkReturnsCommit() {
        String topic = "chunk-topic-a";
        listener.registerChecker(topic, msg -> true);

        RocketMQLocalTransactionState state = listener.checkLocalTransaction(
                buildCheckMessage(topic));

        assertThat(state)
                .as("checker 返回 true 表示本地事务已提交，应 COMMIT")
                .isEqualTo(RocketMQLocalTransactionState.COMMIT);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC07  回查 checker 返回 false → ROLLBACK
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC07 回查：checker.check() 返回 false（DB 状态未提交/已回滚）→ checkLocalTransaction 返回 ROLLBACK")
    void tc07_checkerReturnsFalse_checkReturnsRollback() {
        String topic = "chunk-topic-b";
        listener.registerChecker(topic, msg -> false);

        RocketMQLocalTransactionState state = listener.checkLocalTransaction(
                buildCheckMessage(topic));

        assertThat(state)
                .as("checker 返回 false 表示本地事务未提交，应 ROLLBACK 丢弃消息")
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC08  回查 topic 无对应 checker → ROLLBACK（保守默认）
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC08 回查：topic 无对应 checker（未注册）→ checkLocalTransaction 返回 ROLLBACK（保守兜底）")
    void tc08_noCheckerForTopic_checkReturnsRollback() {
        // 未对 "unknown-topic" 注册任何 checker
        RocketMQLocalTransactionState state = listener.checkLocalTransaction(
                buildCheckMessage("unknown-topic"));

        assertThat(state)
                .as("无 checker 时应保守 ROLLBACK，避免持续投递未知状态的消息")
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 辅助方法
    // ══════════════════════════════════════════════════════════════════════

    /** 构造带 HEADER_TX_ID 的消息（用于 executeLocalTransaction） */
    private static Message<?> buildTxMessage(String txId) {
        return MessageBuilder
                .withPayload(MessageWrapper.builder().keys(txId).body("event-payload").build())
                .setHeader(DelegatingTransactionListener.HEADER_TX_ID, txId)
                .build();
    }

    /** 构造带 HEADER_TOPIC 的消息（用于 checkLocalTransaction） */
    private static Message<?> buildCheckMessage(String topic) {
        MessageWrapper<String> wrapper = MessageWrapper.<String>builder()
                .keys("doc-key").body("chunk-event-body").build();
        return MessageBuilder
                .withPayload(wrapper)
                .setHeader(DelegatingTransactionListener.HEADER_TOPIC, topic)
                .build();
    }
}
