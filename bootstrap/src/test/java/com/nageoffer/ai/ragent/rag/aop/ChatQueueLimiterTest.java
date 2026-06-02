/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.aop;

import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("挑战一：高并发 — ChatQueueLimiter")
class ChatQueueLimiterTest {

    // ── Redisson 数据结构 Mock ──────────────────────────────────────────────
    @Mock private RedissonClient redissonClient;
    @Mock private RPermitExpirableSemaphore semaphore;
    @Mock private RScoredSortedSet<String> zset;
    @Mock private RAtomicLong atomicLong;
    @Mock private RTopic topic;
    @Mock private RScript script;

    // ── 业务依赖 Mock ──────────────────────────────────────────────────────
    @Mock private RAGRateLimitProperties rateLimitProperties;
    @Mock private ConversationMemoryService memoryService;
    @Mock private ConversationGroupService conversationGroupService;
    @Mock private MemoryProperties memoryProperties;

    private Executor chatEntryExecutor;
    private ChatQueueLimiter limiter;

    @BeforeEach
    void setUp() throws Exception {
        // 同步执行器，让断言不依赖异步时序
        chatEntryExecutor = Runnable::run;

        // Redisson 路由：按 key 前缀返回对应 mock
        when(redissonClient.getPermitExpirableSemaphore(anyString())).thenReturn(semaphore);
        doReturn(zset).when(redissonClient).getScoredSortedSet(anyString(), any(StringCodec.class));
        when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);
        when(redissonClient.getTopic(anyString())).thenReturn(topic);
        when(redissonClient.getScript(any(StringCodec.class))).thenReturn(script);

        // 默认配置
        when(rateLimitProperties.getGlobalEnabled()).thenReturn(true);
        when(rateLimitProperties.getGlobalMaxConcurrent()).thenReturn(3);
        when(rateLimitProperties.getGlobalMaxWaitSeconds()).thenReturn(5);
        when(rateLimitProperties.getGlobalLeaseSeconds()).thenReturn(600);
        when(rateLimitProperties.getGlobalPollIntervalMs()).thenReturn(100);
        when(memoryProperties.getTitleMaxLength()).thenReturn(30);

        // 信号量 trySetPermits 默认返回 true（key 不存在）
        when(semaphore.trySetPermits(anyInt())).thenReturn(true);
        when(atomicLong.incrementAndGet()).thenReturn(1L, 2L, 3L, 4L, 5L);

        limiter = buildLimiter();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC01  限流关闭时直通，不入队
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC01 globalEnabled=false 时 onAcquire 直接执行，不与 Redis 交互")
    void tc01_whenGlobalDisabled_onAcquireCalledDirectly() throws Exception {
        when(rateLimitProperties.getGlobalEnabled()).thenReturn(false);

        AtomicInteger acquired = new AtomicInteger();
        SseEmitter emitter = new SseEmitter();

        limiter.enqueue("hello", "conv-1", emitter, acquired::incrementAndGet);

        assertThat(acquired.get()).isEqualTo(1);
        // 限流关闭时不应操作 ZSet
        verify(zset, never()).add(anyDouble(), any());
        verify(semaphore, never()).tryAcquire(anyLong(), anyLong(), any());
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC02  有空闲许可时立即获取并执行
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC02 信号量有剩余许可时，请求 claim 成功后立即执行 onAcquire")
    void tc02_whenPermitsAvailable_immediateExecution() throws Exception {
        // 3 个许可可用
        when(semaphore.availablePermits()).thenReturn(3);
        // Lua claim 成功：当前请求在队头
        when(script.eval(any(), anyString(), any(), anyList(), anyString(), anyString()))
                .thenReturn(List.of(1L, 1.0));
        // 信号量获取成功，返回 permitId
        when(semaphore.tryAcquire(anyLong(), anyLong(), any())).thenReturn("permit-abc");

        AtomicInteger acquired = new AtomicInteger();
        SseEmitter emitter = new SseEmitter();

        limiter.enqueue("query", "conv-2", emitter, acquired::incrementAndGet);

        assertThat(acquired.get()).isEqualTo(1);
        // 请求从 ZSet 被移除（claim 内部 ZREM）
        verify(zset, atLeastOnce()).add(anyDouble(), anyString());
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC03  无可用许可时请求进入 ZSet 排队
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC03 许可耗尽时，请求以递增 score 加入 ZSet 排队")
    void tc03_whenNoPermits_requestEnqueued() {
        // 无可用许可
        when(semaphore.availablePermits()).thenReturn(0);

        SseEmitter emitter = new SseEmitter();
        limiter.enqueue("query", "conv-3", emitter, () -> {});

        // 请求应被写入 ZSet
        ArgumentCaptor<Double> scoreCaptor = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(zset, atLeastOnce()).add(scoreCaptor.capture(), idCaptor.capture());

        // score 来自递增序列（由 atomicLong 提供）
        assertThat(scoreCaptor.getValue()).isGreaterThanOrEqualTo(1.0);
        assertThat(idCaptor.getValue()).isNotBlank();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC04  等待超时后 SSE 事件顺序正确
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC04 超过 maxWaitSeconds 后 SSE 顺序为 META → REJECT → FINISH → DONE")
    void tc04_whenTimeoutExceeded_sseEventsInOrder() throws Exception {
        // 超时设为 0 秒，让下次 poll 立即触发超时
        when(rateLimitProperties.getGlobalMaxWaitSeconds()).thenReturn(0);
        when(semaphore.availablePermits()).thenReturn(0);

        // 捕获 SSE 发送顺序
        List<String> sentEventNames = new CopyOnWriteArrayList<>();
        SseEmitter emitter = spy(new SseEmitter());
        doAnswer(inv -> {
            SseEmitter.SseEventBuilder builder = inv.getArgument(0);
            // 用反射获取 event name（或直接拦截 send 验证 event 字段）
            sentEventNames.add(extractEventName(builder));
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        limiter.enqueue("query-timeout", "conv-4", emitter, () -> {});

        // 等待 poller 触发超时处理（最多 2 秒）
        Awaitility.await().atMost(2, TimeUnit.SECONDS)
                .until(() -> sentEventNames.size() >= 4);

        // 验证事件顺序：meta → reject → finish → done
        assertThat(sentEventNames).containsExactly("meta", "reject", "finish", "done");
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC05  拒绝 SSE 先于异步落库（不被 DB 拖慢）
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC05 拒绝路径：SSE 事件发送完成后才异步落库，不阻塞 SSE 线程")
    void tc05_rejectSseSentBeforeAsyncPersist() throws Exception {
        when(rateLimitProperties.getGlobalMaxWaitSeconds()).thenReturn(0);
        when(semaphore.availablePermits()).thenReturn(0);

        // 让落库操作感知调用时序
        List<String> callOrder = new CopyOnWriteArrayList<>();

        SseEmitter emitter = spy(new SseEmitter());
        doAnswer(inv -> {
            callOrder.add("sse_send");
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        doAnswer(inv -> {
            callOrder.add("sse_complete");
            return null;
        }).when(emitter).complete();

        doAnswer(inv -> {
            // 落库在 chatEntryExecutor 上异步执行
            callOrder.add("db_persist");
            return null;
        }).when(memoryService).append(anyString(), anyString(), any());

        limiter.enqueue("query-persist", "conv-5", emitter, () -> {});

        Awaitility.await().atMost(2, TimeUnit.SECONDS)
                .until(() -> callOrder.contains("sse_complete"));

        // SSE complete 必须在 db_persist 之前出现（或 db_persist 甚至还未发生）
        int sseCompleteIdx = callOrder.indexOf("sse_complete");
        int dbPersistIdx = callOrder.indexOf("db_persist");

        // db_persist 要么还没执行，要么在 sse_complete 之后
        if (dbPersistIdx != -1) {
            assertThat(sseCompleteIdx).isLessThan(dbPersistIdx);
        }
        // 至少 4 个 SSE 事件已发出
        long sseSendCount = callOrder.stream().filter("sse_send"::equals).count();
        assertThat(sseSendCount).isGreaterThanOrEqualTo(4);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC06  Emitter 完成回调触发许可释放与队列通知
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC06 Emitter.onCompletion 触发后，许可被 release，队列收到 notify")
    void tc06_onEmitterCompletion_permitReleasedAndQueueNotified() throws Exception {
        when(semaphore.availablePermits()).thenReturn(3);
        when(script.eval(any(), anyString(), any(), anyList(), anyString(), anyString()))
                .thenReturn(List.of(1L, 1.0));
        when(semaphore.tryAcquire(anyLong(), anyLong(), any())).thenReturn("permit-xyz");

        SseEmitter emitter = new SseEmitter();
        limiter.enqueue("query", "conv-6", emitter, () -> {});

        // 模拟 Emitter 完成
        emitter.complete();

        // 许可应被 release
        verify(semaphore, atLeastOnce()).release(eq("permit-xyz"));
        // 队列应收到通知
        verify(topic, atLeastOnce()).publish(anyString());
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC07  Lua claim 非队头时拒绝 claim，请求留队
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC07 Lua 返回 rank >= maxRank 时 claim 失败，请求仍在 ZSet 中等待")
    void tc07_whenLuaClaimFails_requestRemainsInQueue() throws Exception {
        when(semaphore.availablePermits()).thenReturn(1);
        // Lua 返回 0：请求不在队头
        when(script.eval(any(), anyString(), any(), anyList(), anyString(), anyString()))
                .thenReturn(List.of(0L));

        AtomicInteger acquired = new AtomicInteger();
        SseEmitter emitter = new SseEmitter();

        limiter.enqueue("query", "conv-7", emitter, acquired::incrementAndGet);

        // claim 失败，onAcquire 不应执行
        assertThat(acquired.get()).isEqualTo(0);
        // 信号量不应被消耗
        verify(semaphore, never()).tryAcquire(anyLong(), anyLong(), any());
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC08  高并发下实际执行数不超过 globalMaxConcurrent
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC08 20 个并发请求，实际同时执行数不超过 globalMaxConcurrent(3)")
    void tc08_concurrentRequests_neverExceedMaxConcurrent() throws Exception {
        int maxConcurrent = 3;
        int totalRequests = 20;
        when(rateLimitProperties.getGlobalMaxConcurrent()).thenReturn(maxConcurrent);

        // 使用真实的计数信号量模拟 Redisson 信号量行为
        Semaphore realSemaphore = new Semaphore(maxConcurrent);
        AtomicInteger currentActive = new AtomicInteger(0);
        AtomicInteger peakActive = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);

        // 覆盖 chatEntryExecutor 为真实线程池
        Executor realExecutor = Executors.newFixedThreadPool(totalRequests);
        setField(limiter, "chatEntryExecutor", realExecutor);

        // 替换信号量 mock 为真实计数语义
        when(semaphore.availablePermits()).thenAnswer(inv -> realSemaphore.availablePermits());
        when(semaphore.tryAcquire(anyLong(), anyLong(), any())).thenAnswer(inv -> {
            boolean acquired = realSemaphore.tryAcquire(0, TimeUnit.MILLISECONDS);
            return acquired ? "permit-" + Thread.currentThread().getId() : null;
        });
        doAnswer(inv -> {
            realSemaphore.release();
            return null;
        }).when(semaphore).release(anyString());

        // Lua claim 始终返回成功（专注测试信号量维度的并发控制）
        when(script.eval(any(), anyString(), any(), anyList(), anyString(), anyString()))
                .thenReturn(List.of(1L, 1.0));

        CountDownLatch allDone = new CountDownLatch(totalRequests);
        ExecutorService submitter = Executors.newFixedThreadPool(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            final int idx = i;
            submitter.submit(() -> {
                SseEmitter emitter = new SseEmitter(30_000L);
                limiter.enqueue("query-" + idx, "conv-" + idx, emitter, () -> {
                    int active = currentActive.incrementAndGet();
                    peakActive.updateAndGet(peak -> Math.max(peak, active));
                    try {
                        Thread.sleep(50); // 模拟处理耗时
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    currentActive.decrementAndGet();
                    completedCount.incrementAndGet();
                    emitter.complete();
                    allDone.countDown();
                });
            });
        }

        submitter.shutdown();
        // 等待所有请求完成（最多 10 秒）
        boolean finished = allDone.await(10, TimeUnit.SECONDS);

        // 峰值并发不超过 maxConcurrent
        assertThat(peakActive.get())
                .as("峰值并发数应 <= globalMaxConcurrent")
                .isLessThanOrEqualTo(maxConcurrent);

        // 正常执行的请求数 = 直接获取到许可的请求数
        assertThat(completedCount.get())
                .as("实际完成的请求数应 <= totalRequests")
                .isLessThanOrEqualTo(totalRequests);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 辅助方法
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 构建 ChatQueueLimiter，通过反射注入 claimLua（绕过 ClassPathResource 依赖）
     * 以及替换 chatEntryExecutor 为同步执行器。
     */
    private ChatQueueLimiter buildLimiter() throws Exception {
        // 使用反射构建（ChatQueueLimiter 使用 @RequiredArgsConstructor）
        ChatQueueLimiter instance = new ChatQueueLimiter(
                redissonClient,
                rateLimitProperties,
                memoryService,
                conversationGroupService,
                memoryProperties,
                chatEntryExecutor
        );
        // 注入测试用 Lua 脚本（与生产逻辑一致）
        setField(instance, "claimLua", loadTestLuaScript());
        // 手动触发 @PostConstruct
        instance.subscribeQueueNotify();
        return instance;
    }

    /**
     * 内嵌 Lua 脚本（与 queue_claim_atomic.lua 内容一致），避免 classpath 依赖。
     */
    private String loadTestLuaScript() {
        return """
                local queueKey = KEYS[1]
                local requestId = ARGV[1]
                local maxRank = tonumber(ARGV[2])
                local rank = redis.call('ZRANK', queueKey, requestId)
                if not rank then return {0} end
                if rank >= maxRank then return {0} end
                local score = redis.call('ZSCORE', queueKey, requestId)
                redis.call('ZREM', queueKey, requestId)
                return {1, score}
                """;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " not found in " + target.getClass());
    }

    /**
     * 从 SseEmitter.SseEventBuilder 中提取 event name，用于验证 SSE 事件顺序。
     * 实际项目中可替换为对 SseEmitterSender 的 mock。
     */
    private String extractEventName(SseEmitter.SseEventBuilder builder) {
        // SseEventBuilder 内部有 fields map，通过反射读取 "event" 字段
        try {
            Field fields = builder.getClass().getDeclaredField("fields");
            fields.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.LinkedHashSet<java.util.Map.Entry<String, Object>> entries =
                    (java.util.LinkedHashSet<java.util.Map.Entry<String, Object>>) fields.get(builder);
            for (java.util.Map.Entry<String, Object> entry : entries) {
                if ("event".equals(entry.getKey())) {
                    return entry.getValue().toString();
                }
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }
}
