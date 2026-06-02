/*
 * 挑战二：高可用 — 首包探测（ProbeStreamBridge）单元测试
 *
 * 放置路径（运行前请移动到）：
 *   infra-ai/src/test/java/com/nageoffer/ai/ragent/infra/chat/ProbeStreamBridgeTest.java
 *
 * 注意：ProbeStreamBridge 是 package-private 类，测试必须与其同包。
 *
 * 依赖（已在父 pom 中）：
 *   junit-jupiter, mockito-core（@ExtendWith(MockitoExtension.class)）
 *
 * 测试矩阵（ProbeStreamBridge.ProbeResult 四种类型：SUCCESS / ERROR / NO_CONTENT / TIMEOUT）：
 *   TC-P01  onContent 触发 SUCCESS probe；commit 后 downstream.onContent 被调用
 *   TC-P02  onError 触发 ERROR probe；commit 未发生，downstream.onError 不被调用
 *   TC-P03  onComplete（无内容）触发 NO_CONTENT probe；downstream.onComplete 不被调用
 *   TC-P04  awaitFirstPacket 超时：无任何事件触发，返回 TIMEOUT
 *   TC-P05  SUCCESS commit 后缓冲区按序全量到达 downstream（防止首包后续 token 丢失）
 *
 * 核心设计验证：
 *   - SUCCESS 时先缓冲、后统一 commit，保证 "有效内容先到再推送" 的防脏数据语义
 *   - ERROR / NO_CONTENT / TIMEOUT 不触发 commit，downstream 不收到任何数据
 *   - 确认模型可用后再推送，从根本上防止错误内容外泄给用户
 */

package com.nageoffer.ai.ragent.infra.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("挑战二：高可用 — ProbeStreamBridge 首包探测")
class ProbeStreamBridgeTest {

    @Mock
    private StreamCallback downstream;

    // ══════════════════════════════════════════════════════════════════════
    // TC-P01  onContent → SUCCESS probe；downstream.onContent 在 commit 后被调用
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-P01 onContent 触发 SUCCESS：awaitFirstPacket 返回 SUCCESS 并 commit，downstream 收到内容")
    void tcP01_onContent_successProbeAndDownstreamNotified() throws InterruptedException {
        ProbeStreamBridge bridge = new ProbeStreamBridge(downstream);

        // 先触发 onContent（probe future 立即完成），再 await
        bridge.onContent("hello");

        ProbeStreamBridge.ProbeResult result = bridge.awaitFirstPacket(1, TimeUnit.SECONDS);

        assertThat(result.isSuccess())
                .as("onContent 应触发 SUCCESS probe")
                .isTrue();
        assertThat(result.getType())
                .isEqualTo(ProbeStreamBridge.ProbeResult.Type.SUCCESS);

        // commit 发生后，缓冲中的 onContent 应被分发给 downstream
        verify(downstream).onContent("hello");
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC-P02  onError → ERROR probe；commit 未发生，downstream 不收到错误
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-P02 onError 触发 ERROR：awaitFirstPacket 返回 ERROR，downstream.onError 不被调用（防脏数据外泄）")
    void tcP02_onError_errorProbeAndDownstreamNotNotified() throws InterruptedException {
        ProbeStreamBridge bridge = new ProbeStreamBridge(downstream);
        RuntimeException modelError = new RuntimeException("模型内部错误");

        bridge.onError(modelError);

        ProbeStreamBridge.ProbeResult result = bridge.awaitFirstPacket(1, TimeUnit.SECONDS);

        assertThat(result.getType())
                .as("onError 应触发 ERROR probe")
                .isEqualTo(ProbeStreamBridge.ProbeResult.Type.ERROR);
        assertThat(result.getError())
                .as("probe 应携带原始异常")
                .isSameAs(modelError);

        // 关键断言：ERROR 不触发 commit，downstream 不应收到任何通知
        verify(downstream, never()).onError(any());
        verify(downstream, never()).onContent(anyString());
        verify(downstream, never()).onComplete();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC-P03  onComplete（无内容）→ NO_CONTENT probe；downstream 不被通知
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-P03 onComplete 无内容触发 NO_CONTENT：awaitFirstPacket 返回 NO_CONTENT，downstream 不被调用")
    void tcP03_onCompleteWithoutContent_noContentProbeAndDownstreamSilent() throws InterruptedException {
        ProbeStreamBridge bridge = new ProbeStreamBridge(downstream);

        bridge.onComplete();

        ProbeStreamBridge.ProbeResult result = bridge.awaitFirstPacket(1, TimeUnit.SECONDS);

        assertThat(result.getType())
                .as("未推送任何内容就 onComplete 应触发 NO_CONTENT")
                .isEqualTo(ProbeStreamBridge.ProbeResult.Type.NO_CONTENT);

        // NO_CONTENT 不触发 commit，downstream 不应收到通知
        verify(downstream, never()).onComplete();
        verify(downstream, never()).onContent(anyString());
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC-P04  无事件，awaitFirstPacket 超时 → TIMEOUT probe
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-P04 无任何事件触发：awaitFirstPacket 超时返回 TIMEOUT")
    void tcP04_noEvent_timeoutProbeReturned() throws InterruptedException {
        ProbeStreamBridge bridge = new ProbeStreamBridge(downstream);

        // 超时窗口设为极短（50ms），不触发任何事件
        ProbeStreamBridge.ProbeResult result = bridge.awaitFirstPacket(50, TimeUnit.MILLISECONDS);

        assertThat(result.getType())
                .as("无事件时应返回 TIMEOUT")
                .isEqualTo(ProbeStreamBridge.ProbeResult.Type.TIMEOUT);
        assertThat(result.isSuccess()).isFalse();

        // TIMEOUT 不触发 commit
        verify(downstream, never()).onContent(anyString());
        verify(downstream, never()).onComplete();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC-P05  SUCCESS commit 后缓冲区按序全量到达 downstream（防止 token 丢失）
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-P05 多 token 缓冲：SUCCESS commit 后按入队顺序全量分发给 downstream，无丢失无乱序")
    void tcP05_multipleTokensBufferedAndFlushedInOrder() throws InterruptedException {
        List<String> received = new CopyOnWriteArrayList<>();

        StreamCallback recordingDownstream = new StreamCallback() {
            @Override
            public void onContent(String content) {
                received.add(content);
            }

            @Override
            public void onComplete() {
                received.add("__complete__");
            }

            @Override
            public void onError(Throwable t) {
                received.add("__error__");
            }
        };

        ProbeStreamBridge bridge = new ProbeStreamBridge(recordingDownstream);

        // 在 awaitFirstPacket 之前，多个 token 到达（全部进入 buffer）
        bridge.onContent("token-1");
        bridge.onContent("token-2");
        bridge.onContent("token-3");
        bridge.onComplete();

        ProbeStreamBridge.ProbeResult result = bridge.awaitFirstPacket(1, TimeUnit.SECONDS);

        assertThat(result.isSuccess()).as("首包为 onContent，probe 应为 SUCCESS").isTrue();

        // commit 后，缓冲区应按序全量到达 downstream
        assertThat(received)
                .as("缓冲的 3 个 token 和 complete 应按顺序全量到达")
                .containsExactly("token-1", "token-2", "token-3", "__complete__");
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC-P06（补充）  SUCCESS commit 后，后续 onContent 直接分发不再缓冲
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-P06 commit 后到达的 onContent 直接分发，不再经过 buffer")
    void tcP06_afterCommit_subsequentContentDispatchedDirectly() throws InterruptedException {
        List<String> received = new CopyOnWriteArrayList<>();

        StreamCallback recordingDownstream = new StreamCallback() {
            @Override
            public void onContent(String content) {
                received.add(content);
            }

            @Override
            public void onComplete() {}

            @Override
            public void onError(Throwable t) {}
        };

        ProbeStreamBridge bridge = new ProbeStreamBridge(recordingDownstream);

        // 首包触发 SUCCESS probe
        bridge.onContent("first-token");
        bridge.awaitFirstPacket(1, TimeUnit.SECONDS); // commit 发生

        // commit 之后，异步模拟更多 token 到达
        CompletableFuture.runAsync(() -> bridge.onContent("second-token"));
        CompletableFuture.runAsync(() -> bridge.onContent("third-token"));

        // 等待异步分发完成
        Thread.sleep(100);

        assertThat(received)
                .as("commit 后新到 token 应直接分发，不进 buffer")
                .contains("first-token", "second-token", "third-token");
    }
}
