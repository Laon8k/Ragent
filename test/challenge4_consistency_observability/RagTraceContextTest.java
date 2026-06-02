/*
 * 挑战四：一致性与可观测性 — RagTraceContext 全链路追踪上下文单元测试
 *
 * 放置路径（运行前请移动到）：
 *   bootstrap/src/test/java/com/nageoffer/ai/ragent/framework/trace/RagTraceContextTest.java
 *
 * 依赖（已在父 pom 中）：
 *   junit-jupiter（spring-boot-starter-test 已涵盖）
 *
 * 测试矩阵：
 *   TC01  初始状态：getTraceId()=null，depth()=0，currentNodeId()=null
 *   TC02  setTraceId / getTraceId 读写闭环
 *   TC03  pushNode 一次 → depth=1，currentNodeId = 压入的 nodeId
 *   TC04  pushNode 两次 → depth=2，currentNodeId = 最后压入（LIFO 栈）
 *   TC05  popNode → 深度递减，currentNodeId 回退到前一个节点
 *   TC06  clear() → 所有 ThreadLocal 状态清零（traceId、nodeStack）
 *   TC07  五阶段 RAG 链路模拟：query-rewrite → intent-classify → retrieve → rerank
 *         → stream-output → 逐层 pop → 栈空，traceId 仍保留直到 clear()
 *
 * 核心设计验证（对应 PPT 挑战四）：
 *   - TransmittableThreadLocal 透传 traceId / nodeStack，支持异步线程池全链路追踪
 *   - 节点栈（push/pop）形成树形 parent-child 关系，对应 PPT 中各 RAG 阶段
 *   - clear() 精确清理，防止线程池复用导致的 ThreadLocal 泄漏
 */

package com.nageoffer.ai.ragent.framework.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("挑战四：一致性与可观测性 — RagTraceContext 全链路追踪上下文")
class RagTraceContextTest {

    @BeforeEach
    @AfterEach
    void resetContext() {
        // 保证每个测试前后都是干净状态，防止 ThreadLocal 泄漏影响其他用例
        RagTraceContext.clear();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC01  初始状态
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC01 初始状态：getTraceId()=null，depth()=0，currentNodeId()=null")
    void tc01_freshContext_allNullOrZero() {
        assertThat(RagTraceContext.getTraceId())
                .as("链路初始无 traceId")
                .isNull();
        assertThat(RagTraceContext.depth())
                .as("初始节点栈深度为 0")
                .isZero();
        assertThat(RagTraceContext.currentNodeId())
                .as("初始无当前节点")
                .isNull();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC02  setTraceId / getTraceId 读写闭环
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC02 setTraceId / getTraceId 闭环：设置后可读取，值与输入完全一致")
    void tc02_setAndGetTraceId_roundTrip() {
        String traceId = "trace-20260602-001";
        RagTraceContext.setTraceId(traceId);

        assertThat(RagTraceContext.getTraceId())
                .as("读取的 traceId 应与写入完全一致")
                .isEqualTo(traceId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC03  pushNode 一次 → depth=1，currentNodeId 正确
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC03 pushNode 一次：depth=1，currentNodeId = 压入的 nodeId")
    void tc03_pushOneNode_depthAndCurrentNodeCorrect() {
        String nodeId = "node-query-rewrite";
        RagTraceContext.pushNode(nodeId);

        assertThat(RagTraceContext.depth())
                .as("压入 1 个节点后 depth 应为 1")
                .isEqualTo(1);
        assertThat(RagTraceContext.currentNodeId())
                .as("currentNodeId 应为最新压入的节点")
                .isEqualTo(nodeId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC04  pushNode 两次 → LIFO：currentNodeId = 最后压入
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC04 pushNode 两次（LIFO）：depth=2，currentNodeId = 第二个压入（最新节点）")
    void tc04_pushTwoNodes_lifoOrder() {
        RagTraceContext.pushNode("node-intent-classify");
        RagTraceContext.pushNode("node-retrieve");

        assertThat(RagTraceContext.depth())
                .as("两层节点 depth 应为 2")
                .isEqualTo(2);
        assertThat(RagTraceContext.currentNodeId())
                .as("LIFO 栈：currentNodeId 应为最后压入的 node-retrieve")
                .isEqualTo("node-retrieve");
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC05  popNode → depth 递减，currentNodeId 回退
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC05 popNode：depth 递减，currentNodeId 恢复到前一个节点（父节点视图）")
    void tc05_popNode_restoresPreviousState() {
        String parentNodeId = "node-intent-classify";
        RagTraceContext.pushNode(parentNodeId);
        RagTraceContext.pushNode("node-retrieve");

        RagTraceContext.popNode();

        assertThat(RagTraceContext.depth())
                .as("pop 后 depth 应从 2 降为 1")
                .isEqualTo(1);
        assertThat(RagTraceContext.currentNodeId())
                .as("pop 后 currentNodeId 应回退到父节点")
                .isEqualTo(parentNodeId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC06  clear() → 全部 ThreadLocal 清零
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC06 clear()：清空 traceId 与节点栈，防止线程池复用时 ThreadLocal 泄漏")
    void tc06_clear_resetsAllState() {
        RagTraceContext.setTraceId("trace-leak-test");
        RagTraceContext.pushNode("node-before-clear");

        RagTraceContext.clear();

        assertThat(RagTraceContext.getTraceId())
                .as("clear 后 traceId 应为 null")
                .isNull();
        assertThat(RagTraceContext.depth())
                .as("clear 后节点栈深度应为 0")
                .isZero();
        assertThat(RagTraceContext.currentNodeId())
                .as("clear 后 currentNodeId 应为 null")
                .isNull();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC07  五阶段 RAG 链路模拟
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC07 五阶段 RAG 链路：query-rewrite→intent-classify→retrieve→rerank→stream-output，pop 后栈空")
    void tc07_fiveStageRagPipeline_stackManagedCorrectly() {
        // 模拟 RagTraceAspect.aroundRoot 创建 traceId
        String traceId = "trace-rag-pipeline-001";
        RagTraceContext.setTraceId(traceId);
        assertThat(RagTraceContext.depth()).isZero();

        // Stage 1: query-rewrite
        RagTraceContext.pushNode("node-query-rewrite");
        assertThat(RagTraceContext.depth()).isEqualTo(1);
        assertThat(RagTraceContext.currentNodeId()).isEqualTo("node-query-rewrite");

        // Stage 2: intent-classify（嵌套在 query-rewrite 之后，作为同层节点）
        RagTraceContext.popNode();
        RagTraceContext.pushNode("node-intent-classify");
        assertThat(RagTraceContext.depth()).isEqualTo(1);
        assertThat(RagTraceContext.currentNodeId()).isEqualTo("node-intent-classify");

        // Stage 3: retrieve
        RagTraceContext.popNode();
        RagTraceContext.pushNode("node-retrieve");

        // Stage 4: rerank（嵌套在 retrieve 内部，表示子阶段）
        RagTraceContext.pushNode("node-rerank");
        assertThat(RagTraceContext.depth())
                .as("rerank 嵌套在 retrieve 内，深度应为 2")
                .isEqualTo(2);

        // Stage 5: stream-output（rerank 完成后 pop，再进 stream-output）
        RagTraceContext.popNode();    // pop rerank
        RagTraceContext.popNode();    // pop retrieve
        RagTraceContext.pushNode("node-stream-output");
        assertThat(RagTraceContext.depth()).isEqualTo(1);
        assertThat(RagTraceContext.currentNodeId()).isEqualTo("node-stream-output");

        // 链路结束：pop stream-output，栈清空
        RagTraceContext.popNode();

        assertThat(RagTraceContext.depth())
                .as("所有节点 pop 后栈深度应为 0")
                .isZero();
        assertThat(RagTraceContext.currentNodeId())
                .as("所有节点 pop 后 currentNodeId 应为 null")
                .isNull();
        assertThat(RagTraceContext.getTraceId())
                .as("traceId 在 clear() 前应仍然保留（由 aroundRoot finally 负责清理）")
                .isEqualTo(traceId);

        // aroundRoot finally 块清理
        RagTraceContext.clear();
        assertThat(RagTraceContext.getTraceId())
                .as("clear 后 traceId 应清零，防止线程池线程复用产生幽灵 trace")
                .isNull();
    }
}
