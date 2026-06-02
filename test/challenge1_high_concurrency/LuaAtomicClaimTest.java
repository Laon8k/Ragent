/*
 * 挑战一：高并发 — Lua 原子 claim 脚本测试
 *
 * 放置路径：
 *   bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/aop/LuaAtomicClaimTest.java
 *
 * 依赖：testcontainers-redis（或 embedded-redis）
 *   <dependency>
 *     <groupId>com.redis</groupId>
 *     <artifactId>testcontainers-redis</artifactId>
 *     <version>2.2.2</version>
 *     <scope>test</scope>
 *   </dependency>
 *
 * 这组测试直接对 Redis 执行 Lua 脚本，验证：
 *   TC-L01  requestId 不在队列时返回 {0}
 *   TC-L02  requestId 在队列但 rank >= maxRank 时返回 {0}（非队头）
 *   TC-L03  requestId 在队头（rank < maxRank）时成功 claim，返回 {1, score}，并从 ZSet 移除
 *   TC-L04  两个并发调用同一个 requestId，只有一个 claim 成功（原子性）
 *   TC-L05  claim 成功后 ZSet 中原 requestId 不再存在
 */

package com.nageoffer.ai.ragent.rag.aop;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DisplayName("挑战一：高并发 — Lua 原子 claim 脚本验证")
class LuaAtomicClaimTest {

    private static final String QUEUE_KEY = "rag:test:queue";

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private Jedis jedis;

    /** 与 queue_claim_atomic.lua 内容一致 */
    private static final String CLAIM_LUA = """
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

    @BeforeEach
    void connect() {
        jedis = new Jedis(redis.getHost(), redis.getFirstMappedPort());
        jedis.flushAll();
    }

    @AfterEach
    void disconnect() {
        if (jedis != null) jedis.close();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC-L01  requestId 不在队列时返回 {0}
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-L01 requestId 不在 ZSet 时 Lua 返回 {0}（miss）")
    void tcL01_notInQueue_returnsMiss() {
        @SuppressWarnings("unchecked")
        List<Long> result = (List<Long>) jedis.eval(
                CLAIM_LUA, List.of(QUEUE_KEY), List.of("ghost-id", "3"));

        assertThat(result).containsExactly(0L);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC-L02  rank >= maxRank 时拒绝 claim（非队头）
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-L02 rank >= maxRank 时返回 {0}（非队头不可 claim）")
    void tcL02_rankExceedsMaxRank_returnsMiss() {
        // 队列有 5 个请求，maxRank=2（只允许前 2 个通过）
        for (int i = 0; i < 5; i++) {
            jedis.zadd(QUEUE_KEY, i, "req-" + i);
        }
        // req-4 的 rank=4，maxRank=2 → 拒绝
        @SuppressWarnings("unchecked")
        List<Long> result = (List<Long>) jedis.eval(
                CLAIM_LUA, List.of(QUEUE_KEY), List.of("req-4", "2"));

        assertThat(result).containsExactly(0L);
        // req-4 仍在队列
        assertThat(jedis.zscore(QUEUE_KEY, "req-4")).isNotNull();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC-L03  队头 claim 成功，返回 {1, score}，从 ZSet 移除
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-L03 rank=0 < maxRank=1 时 claim 成功，返回 {1, score}，requestId 从 ZSet 移除")
    void tcL03_queueHead_claimSucceeds() {
        jedis.zadd(QUEUE_KEY, 42.0, "req-head");
        jedis.zadd(QUEUE_KEY, 43.0, "req-second");

        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) jedis.eval(
                CLAIM_LUA, List.of(QUEUE_KEY), List.of("req-head", "1"));

        // 返回 [1, "42"]
        assertThat(result.get(0)).isEqualTo(1L);
        assertThat(Double.parseDouble(result.get(1).toString())).isEqualTo(42.0);

        // req-head 已被移除
        assertThat(jedis.zscore(QUEUE_KEY, "req-head")).isNull();
        // req-second 仍在队列
        assertThat(jedis.zscore(QUEUE_KEY, "req-second")).isEqualTo(43.0);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC-L04  并发调用：同一 requestId 只被 claim 一次（原子性）
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-L04 10 线程并发 claim 同一 requestId，只有 1 个成功（Lua 原子性保证）")
    void tcL04_concurrentClaim_onlyOneSucceeds() throws InterruptedException {
        jedis.zadd(QUEUE_KEY, 1.0, "shared-req");

        int threads = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        var executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // 每个线程独立 Jedis 连接
                    try (Jedis conn = new Jedis(redis.getHost(), redis.getFirstMappedPort())) {
                        @SuppressWarnings("unchecked")
                        List<Object> r = (List<Object>) conn.eval(
                                CLAIM_LUA, List.of(QUEUE_KEY), List.of("shared-req", "1"));
                        if (!r.isEmpty() && Long.parseLong(r.get(0).toString()) == 1L) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Lua 原子性保证：仅 1 个线程 claim 成功
        assertThat(successCount.get())
                .as("Lua eval 是原子的，同一 requestId 只能被 claim 一次")
                .isEqualTo(1);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC-L05  连续 claim 验证公平队列推进
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC-L05 按 score 顺序依次 claim，验证公平 FIFO 排队推进")
    void tcL05_fifoOrder_claimsByScore() {
        // 5 个请求按顺序入队
        for (int i = 0; i < 5; i++) {
            jedis.zadd(QUEUE_KEY, i, "req-" + i);
        }

        // maxRank=1：每次只允许队头 claim
        for (int i = 0; i < 5; i++) {
            @SuppressWarnings("unchecked")
            List<Object> result = (List<Object>) jedis.eval(
                    CLAIM_LUA, List.of(QUEUE_KEY), List.of("req-" + i, "1"));

            assertThat(result.get(0)).as("req-%d 应成为队头并 claim 成功", i).isEqualTo(1L);
            assertThat(Double.parseDouble(result.get(1).toString()))
                    .as("req-%d 的 score 应为 %d", i, i).isEqualTo((double) i);

            // claim 后下一个请求成为新队头
        }

        // 全部 claim 后队列为空
        assertThat(jedis.zcard(QUEUE_KEY)).isEqualTo(0L);
    }
}
