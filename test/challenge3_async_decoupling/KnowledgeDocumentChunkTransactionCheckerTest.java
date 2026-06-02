/*
 * 挑战三：异步解耦 — KnowledgeDocumentChunkTransactionChecker 回查逻辑单元测试
 *
 * 放置路径（运行前请移动到）：
 *   bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkTransactionCheckerTest.java
 *
 * 依赖（已在父 pom 中）：
 *   junit-jupiter, mockito-core, spring-boot-starter-test
 *
 * 测试矩阵：
 *   TC-C01  文档状态为 RUNNING → check 返回 true（本地事务已提交，可 COMMIT 消息）
 *   TC-C02  文档状态为 SUCCESS  → check 返回 false（状态异常，保守 ROLLBACK）
 *   TC-C03  文档状态为 PENDING  → check 返回 false（事务未提交，ROLLBACK 丢弃）
 *   TC-C04  文档不存在（DB 返回 null） → check 返回 false（保守 ROLLBACK）
 *   TC-C05  端到端路由验证：registerChecker 注册到 DelegatingTransactionListener
 *           回查消息携带对应 topic → checkLocalTransaction 返回 COMMIT（RUNNING 状态）
 *
 * 核心设计验证（对应 PPT 挑战三）：
 *   - Broker 回查走 DB，不依赖内存，多实例可水平扩展
 *   - 仅 RUNNING 状态被认定为"本地事务已提交"，避免误投递
 *   - 文档不存在或状态异常时保守 ROLLBACK，消息安全丢弃
 */

package com.nageoffer.ai.ragent.knowledge.mq;

import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("挑战三：异步解耦 — KnowledgeDocumentChunkTransactionChecker 回查逻辑")
class KnowledgeDocumentChunkTransactionCheckerTest {

    private static final String DOC_ID = "doc-test-001";
    private static final String CHUNK_TOPIC = "knowledge-document-chunk_topic";

    @Mock
    private KnowledgeDocumentMapper documentMapper;

    /** 手动构造，避免 Spring 容器依赖 */
    private KnowledgeDocumentChunkTransactionChecker checker;

    @BeforeEach
    void setUp() {
        checker = new KnowledgeDocumentChunkTransactionChecker(documentMapper, null);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC-C01  状态 RUNNING → check 返回 true（事务已提交，消息可投递）
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-C01 文档状态 RUNNING：check 返回 true，Broker 应 COMMIT 消息")
    void tcC01_statusRunning_returnsTrue() {
        KnowledgeDocumentDO doc = buildDoc(DOC_ID, DocumentStatus.RUNNING.getCode());
        when(documentMapper.selectById(eq(DOC_ID))).thenReturn(doc);

        boolean result = checker.check(buildEventWrapper(DOC_ID));

        assertThat(result)
                .as("RUNNING 状态表示本地事务已提交，回查应返回 true 触发 COMMIT")
                .isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC-C02  状态 SUCCESS → check 返回 false（消费者已处理完，无需重投递）
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-C02 文档状态 SUCCESS：check 返回 false，避免消息重复投递")
    void tcC02_statusSuccess_returnsFalse() {
        KnowledgeDocumentDO doc = buildDoc(DOC_ID, DocumentStatus.SUCCESS.getCode());
        when(documentMapper.selectById(eq(DOC_ID))).thenReturn(doc);

        boolean result = checker.check(buildEventWrapper(DOC_ID));

        assertThat(result)
                .as("SUCCESS 状态：消息已处理完成，回查返回 false 保守处理")
                .isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC-C03  状态 PENDING → check 返回 false（本地事务未提交）
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-C03 文档状态 PENDING：check 返回 false，本地事务尚未提交，ROLLBACK 丢弃")
    void tcC03_statusPending_returnsFalse() {
        KnowledgeDocumentDO doc = buildDoc(DOC_ID, DocumentStatus.PENDING.getCode());
        when(documentMapper.selectById(eq(DOC_ID))).thenReturn(doc);

        boolean result = checker.check(buildEventWrapper(DOC_ID));

        assertThat(result)
                .as("PENDING 状态：本地事务未提交，应返回 false 触发 ROLLBACK")
                .isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC-C04  文档不存在（DB 返回 null）→ check 返回 false（保守 ROLLBACK）
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-C04 文档不存在（DB 返回 null）：check 返回 false，避免投递孤儿消息")
    void tcC04_documentNotFound_returnsFalse() {
        when(documentMapper.selectById(eq(DOC_ID))).thenReturn(null);

        boolean result = checker.check(buildEventWrapper(DOC_ID));

        assertThat(result)
                .as("文档不存在时应保守返回 false，消息安全丢弃，不产生孤儿消息")
                .isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC-C05  端到端路由验证：checker 注册后，DelegatingTransactionListener 可正确路由回查
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-C05 端到端路由：checker 注册到 DelegatingTransactionListener，回查消息按 topic 路由返回 COMMIT")
    void tcC05_endToEnd_checkerRegisteredAndRouted() {
        // checkLocalTransaction 不调用 transactionManager，可用无参构造直接实例化
        DelegatingTransactionListener listener = new DelegatingTransactionListener();

        // 手动注册 checker：DB 文档为 RUNNING 状态
        KnowledgeDocumentDO doc = buildDoc(DOC_ID, DocumentStatus.RUNNING.getCode());
        when(documentMapper.selectById(eq(DOC_ID))).thenReturn(doc);
        listener.registerChecker(CHUNK_TOPIC, checker);

        // 构造 Broker 回查消息
        Message<?> checkMsg = buildCheckMessage(CHUNK_TOPIC, DOC_ID);
        RocketMQLocalTransactionState state = listener.checkLocalTransaction(checkMsg);

        assertThat(state)
                .as("DB 状态 RUNNING，端到端回查应路由到 checker 并返回 COMMIT")
                .isEqualTo(RocketMQLocalTransactionState.COMMIT);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 辅助方法
    // ══════════════════════════════════════════════════════════════════════

    private static KnowledgeDocumentDO buildDoc(String id, String status) {
        return KnowledgeDocumentDO.builder().id(id).status(status).build();
    }

    private static MessageWrapper<?> buildEventWrapper(String docId) {
        KnowledgeDocumentChunkEvent event = KnowledgeDocumentChunkEvent.builder()
                .docId(docId).kbId("kb-001").operator("test-user").build();
        return MessageWrapper.<KnowledgeDocumentChunkEvent>builder()
                .keys(docId).body(event).build();
    }

    private static Message<?> buildCheckMessage(String topic, String docId) {
        KnowledgeDocumentChunkEvent event = KnowledgeDocumentChunkEvent.builder()
                .docId(docId).kbId("kb-001").operator("test-user").build();
        MessageWrapper<KnowledgeDocumentChunkEvent> wrapper = MessageWrapper
                .<KnowledgeDocumentChunkEvent>builder()
                .keys(docId).body(event).build();
        return MessageBuilder.withPayload(wrapper)
                .setHeader("TRANSACTION_TOPIC", topic)
                .build();
    }
}
