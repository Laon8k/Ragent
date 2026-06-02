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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.enums.IntentLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("挑战四：一致性与可观测性 — IntentTreeCacheManager Redis 缓存意图树")
class IntentTreeCacheManagerTest {

    private static final String CACHE_KEY = "ragent:intent:tree";

    @InjectMocks
    private IntentTreeCacheManager cacheManager;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    @SuppressWarnings("rawtypes")
    private ValueOperations valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC01  缓存未命中 → 返回 null
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC01 Redis 返回 null（缓存未命中）：getIntentTreeFromCache 返回 null，触发 DB 回退")
    @SuppressWarnings("unchecked")
    void tc01_cacheMiss_returnsNull() {
        when(valueOps.get(CACHE_KEY)).thenReturn(null);

        List<IntentNode> result = cacheManager.getIntentTreeFromCache();

        assertThat(result)
                .as("缓存未命中应返回 null，由调用方决定是否从 DB 加载")
                .isNull();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC02  缓存命中 → 反序列化节点列表
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC02 缓存命中：Redis JSON 正确反序列化为 IntentNode 列表，id/name 与原始一致")
    @SuppressWarnings("unchecked")
    void tc02_cacheHit_deserializesCorrectly() throws Exception {
        String cachedJson = "[{\"id\":\"domain-hr\",\"name\":\"人事\"}]";
        IntentNode expected = buildNode("domain-hr", "人事");

        when(valueOps.get(CACHE_KEY)).thenReturn(cachedJson);
        when(objectMapper.readValue(eq(cachedJson), any(TypeReference.class)))
                .thenReturn(List.of(expected));

        List<IntentNode> result = cacheManager.getIntentTreeFromCache();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).as("节点 id 应与原始一致").isEqualTo("domain-hr");
        assertThat(result.get(0).getName()).as("节点 name 应与原始一致").isEqualTo("人事");
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC03  saveIntentTreeToCache → 写入正确 key 与 7 天 TTL
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC03 saveIntentTreeToCache：调用 set(key, json, 7, DAYS)，TTL=7 天匹配配置")
    @SuppressWarnings("unchecked")
    void tc03_save_writesCorrectKeyAndTtl() throws Exception {
        IntentNode node = buildNode("domain-hr", "人事");
        String serializedJson = "[{\"id\":\"domain-hr\"}]";
        when(objectMapper.writeValueAsString(any())).thenReturn(serializedJson);

        cacheManager.saveIntentTreeToCache(List.of(node));

        verify(valueOps).set(
                eq(CACHE_KEY),
                eq(serializedJson),
                eq(7L),
                eq(TimeUnit.DAYS)
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC04  clearIntentTreeCache（key 存在）→ 正常删除
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC04 clearIntentTreeCache（key 存在，delete=true）：正常清除，调用方无异常")
    void tc04_clear_keyExists_deletesSuccessfully() {
        when(stringRedisTemplate.delete(CACHE_KEY)).thenReturn(true);

        assertThatNoException()
                .as("key 存在时清除应无异常")
                .isThrownBy(() -> cacheManager.clearIntentTreeCache());

        verify(stringRedisTemplate).delete(eq(CACHE_KEY));
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC05  clearIntentTreeCache（key 不存在）→ 静默忽略
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC05 clearIntentTreeCache（key 不存在，delete=false）：静默忽略，无异常，不影响后续流程")
    void tc05_clear_keyAbsent_silentNoOp() {
        when(stringRedisTemplate.delete(CACHE_KEY)).thenReturn(false);

        assertThatNoException()
                .as("key 不存在时清除应静默忽略")
                .isThrownBy(() -> cacheManager.clearIntentTreeCache());
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC06  isCacheExists → 正确反映 Redis hasKey 结果
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC06 isCacheExists：hasKey=true 返回 true；hasKey=false 返回 false（多实例可靠读取状态）")
    void tc06_isCacheExists_reflectsRedisHasKey() {
        when(stringRedisTemplate.hasKey(CACHE_KEY)).thenReturn(true);
        assertThat(cacheManager.isCacheExists())
                .as("Redis hasKey=true，isCacheExists 应返回 true")
                .isTrue();

        when(stringRedisTemplate.hasKey(CACHE_KEY)).thenReturn(false);
        assertThat(cacheManager.isCacheExists())
                .as("Redis hasKey=false，isCacheExists 应返回 false")
                .isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC07  Redis 抛异常 → 防御性返回 null，不上抛
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC07 Redis 连接异常：getIntentTreeFromCache 防御性返回 null，缓存故障不中断意图分类链路")
    @SuppressWarnings("unchecked")
    void tc07_redisException_returnsNullSafely() {
        when(valueOps.get(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        assertThatNoException()
                .as("Redis 异常不应上抛，由调用方降级处理")
                .isThrownBy(() -> cacheManager.getIntentTreeFromCache());

        List<IntentNode> result = cacheManager.getIntentTreeFromCache();
        assertThat(result)
                .as("Redis 异常时应防御性返回 null，触发 DB 降级加载")
                .isNull();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 辅助方法
    // ══════════════════════════════════════════════════════════════════════

    private static IntentNode buildNode(String id, String name) {
        return IntentNode.builder()
                .id(id)
                .name(name)
                .level(IntentLevel.DOMAIN)
                .kind(IntentKind.KB)
                .build();
    }
}
