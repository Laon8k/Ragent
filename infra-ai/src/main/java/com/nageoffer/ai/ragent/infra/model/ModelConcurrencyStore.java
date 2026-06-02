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

package com.nageoffer.ai.ragent.infra.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型并发槽位管理器
 * <p>
 * 维护每个模型 ID 的当前活跃请求计数，支持按上限进行容量感知路由：
 * - tryAcquire：尝试占用一个槽位，已满返回 false（不阻塞）
 * - forceAcquire：强制占位（所有候选均满时的兜底）
 * - release：释放槽位（流式场景在 onComplete/onError 中调用）
 */
@Slf4j
@Component
public class ModelConcurrencyStore {

    private final Map<String, AtomicInteger> activeCountMap = new ConcurrentHashMap<>();

    /**
     * 尝试获取槽位。maxConcurrent <= 0 表示不限制，直接返回 true。
     */
    public boolean tryAcquire(String modelId, int maxConcurrent) {
        if (maxConcurrent <= 0) {
            return true;
        }
        AtomicInteger counter = activeCountMap.computeIfAbsent(modelId, k -> new AtomicInteger(0));
        int current;
        do {
            current = counter.get();
            if (current >= maxConcurrent) {
                log.debug("Model at capacity, skip. modelId={}, active={}/{}", modelId, current, maxConcurrent);
                return false;
            }
        } while (!counter.compareAndSet(current, current + 1));
        log.debug("Model concurrency acquired. modelId={}, active={}/{}", modelId, current + 1, maxConcurrent);
        return true;
    }

    /**
     * 强制占位（忽略上限），用于所有候选均满时的兜底。
     */
    public void forceAcquire(String modelId, int maxConcurrent) {
        if (maxConcurrent <= 0) {
            return;
        }
        int active = activeCountMap.computeIfAbsent(modelId, k -> new AtomicInteger(0)).incrementAndGet();
        log.debug("Model concurrency force-acquired. modelId={}, active={}/{}", modelId, active, maxConcurrent);
    }

    /**
     * 释放槽位。
     */
    public void release(String modelId, int maxConcurrent) {
        if (maxConcurrent <= 0) {
            return;
        }
        AtomicInteger counter = activeCountMap.get(modelId);
        if (counter != null) {
            int active = counter.decrementAndGet();
            log.debug("Model concurrency released. modelId={}, active={}/{}", modelId, active, maxConcurrent);
        }
    }

    /**
     * 查询是否还有空余容量（不占位，仅供排序使用）。
     */
    public boolean hasCapacity(String modelId, int maxConcurrent) {
        if (maxConcurrent <= 0) {
            return true;
        }
        AtomicInteger counter = activeCountMap.get(modelId);
        return counter == null || counter.get() < maxConcurrent;
    }
}
