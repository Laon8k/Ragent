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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.controller.vo.IntentBenchmarkVO;
import com.nageoffer.ai.ragent.rag.core.intent.DefaultIntentClassifier;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 意图树缓存效果基准对比服务
 * <p>
 * 分别对「Redis 缓存」和「直接查库」各跑 N 轮，
 * 统计加载耗时，输出对比报告。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentTreeBenchmarkService {

    private final DefaultIntentClassifier intentClassifier;
    private final IntentTreeCacheManager intentTreeCacheManager;

    /**
     * 运行对比基准测试
     *
     * @param rounds 每组轮数（建议 5~20）
     */
    public IntentBenchmarkVO benchmark(int rounds) {
        int safeRounds = Math.max(1, Math.min(rounds, 50));

        // ── 第一组：有缓存（先预热一轮保证 key 存在）──────────────────────
        log.info("[Benchmark] 开始有缓存组，预热...");
        intentClassifier.loadTreeTimed(true); // 预热，确保 Redis 有数据

        List<Long> cacheMs = new ArrayList<>(safeRounds);
        int cacheNodeCount = 0;
        int cacheLeafCount = 0;
        for (int i = 0; i < safeRounds; i++) {
            DefaultIntentClassifier.TreeLoadResult r = intentClassifier.loadTreeTimed(true);
            cacheMs.add(r.loadMs());
            cacheNodeCount = r.allNodes().size();
            cacheLeafCount = r.leafNodes().size();
            log.info("[Benchmark] 有缓存 round={} source={} loadMs={}", i + 1, r.source(), r.loadMs());
        }

        // ── 第二组：无缓存（清除 Redis key，强制走数据库）────────────────
        log.info("[Benchmark] 清除 Redis 缓存，开始无缓存组...");
        intentTreeCacheManager.clearIntentTreeCache();

        List<Long> dbMs = new ArrayList<>(safeRounds);
        int dbNodeCount = 0;
        int dbLeafCount = 0;
        for (int i = 0; i < safeRounds; i++) {
            DefaultIntentClassifier.TreeLoadResult r = intentClassifier.loadTreeTimed(false);
            dbMs.add(r.loadMs());
            dbNodeCount = r.allNodes().size();
            dbLeafCount = r.leafNodes().size();
            log.info("[Benchmark] 无缓存 round={} source={} loadMs={}", i + 1, r.source(), r.loadMs());
        }

        // ── 测试结束：重新预热缓存供后续请求使用 ─────────────────────────
        intentClassifier.loadTreeTimed(true);
        log.info("[Benchmark] 测试完成，已重新预热 Redis 缓存");

        // ── 统计 ──────────────────────────────────────────────────────────
        IntentBenchmarkVO.Group withCache = buildGroup("redis_cache", cacheMs, cacheNodeCount, cacheLeafCount);
        IntentBenchmarkVO.Group withoutCache = buildGroup("database", dbMs, dbNodeCount, dbLeafCount);

        long savedAvgMs = withoutCache.getAvgMs() - withCache.getAvgMs();
        String ratio = withCache.getAvgMs() == 0
                ? "∞"
                : String.format("%.1fx", (double) withoutCache.getAvgMs() / withCache.getAvgMs());

        return IntentBenchmarkVO.builder()
                .withCache(withCache)
                .withoutCache(withoutCache)
                .speedupRatio(ratio)
                .savedAvgMs(savedAvgMs)
                .build();
    }

    private IntentBenchmarkVO.Group buildGroup(String source, List<Long> times, int nodeCount, int leafCount) {
        List<Long> sorted = new ArrayList<>(times);
        Collections.sort(sorted);

        long sum = sorted.stream().mapToLong(Long::longValue).sum();
        long avg = sorted.isEmpty() ? 0 : sum / sorted.size();
        long min = sorted.isEmpty() ? 0 : sorted.get(0);
        long max = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);
        long p90 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.9));

        return IntentBenchmarkVO.Group.builder()
                .source(source)
                .rounds(times.size())
                .nodeCount(nodeCount)
                .leafCount(leafCount)
                .roundMs(times)
                .avgMs(avg)
                .minMs(min)
                .maxMs(max)
                .p90Ms(p90)
                .build();
    }
}
