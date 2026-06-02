/*
 * 挑战二：高可用 — 三态熔断器 + 路由故障转移单元测试
 *
 * 放置路径（运行前请移动到）：
 *   infra-ai/src/test/java/com/nageoffer/ai/ragent/infra/model/ModelCircuitBreakerTest.java
 *
 * 依赖（已在父 pom 中）：
 *   junit-jupiter, assertj-core（spring-boot-starter-test 已涵盖）
 *
 * 测试矩阵：
 *   TC01  初始状态 allowCall=true（CLOSED 默认值）
 *   TC02  单次失败低于阈值，仍处于 CLOSED
 *   TC03  连续失败达阈值，熔断打开（CLOSED → OPEN）
 *   TC04  OPEN 期间持续拒绝调用
 *   TC05  OPEN 到期后：仅首次 allowCall=true（HALF_OPEN probe），第二次=false（in-flight 保护）
 *   TC06  HALF_OPEN 成功：恢复 CLOSED，重置所有计数
 *   TC07  HALF_OPEN 失败：重新熔断，halfOpenInFlight 重置后允许下一轮探测
 *   TC08  ModelRoutingExecutor：首选模型抛异常，自动故障转移至备用模型
 */

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("挑战二：高可用 — 三态熔断器与路由故障转移")
class ModelCircuitBreakerTest {

    private static final int FAILURE_THRESHOLD = 2;

    /** 默认 store：OPEN 持续时间极长，不会自然过期 */
    private ModelHealthStore store;
    private ModelRoutingExecutor executor;

    @BeforeEach
    void setUp() {
        store = buildStore(FAILURE_THRESHOLD, 999_999_999L);
        executor = new ModelRoutingExecutor(store, new ModelConcurrencyStore());
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC01  初始状态 allowCall=true（CLOSED 起始）
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC01 未知模型首次调用：CLOSED 初始，allowCall=true，isUnavailable=false")
    void tc01_freshModel_allowedByDefault() {
        assertThat(store.allowCall("brand-new-model"))
                .as("初次调用默认 CLOSED，应允许")
                .isTrue();
        assertThat(store.isUnavailable("brand-new-model"))
                .as("初始不应标记不可用")
                .isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC02  单次失败低于阈值，保持 CLOSED
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC02 1 次失败 < 阈值 " + FAILURE_THRESHOLD + "：保持 CLOSED，allowCall=true")
    void tc02_singleFailureBelowThreshold_staysClosed() {
        store.markFailure("model-b");

        assertThat(store.allowCall("model-b"))
                .as("未达阈值，不触发熔断")
                .isTrue();
        assertThat(store.isUnavailable("model-b"))
                .isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC03  连续失败达阈值，CLOSED → OPEN
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC03 连续 " + FAILURE_THRESHOLD + " 次失败：CLOSED → OPEN，allowCall=false，isUnavailable=true")
    void tc03_consecutiveFailuresReachThreshold_opensCircuit() {
        triggerOpen(store, "model-c");

        assertThat(store.allowCall("model-c"))
                .as("OPEN 状态应拒绝新调用")
                .isFalse();
        assertThat(store.isUnavailable("model-c"))
                .as("OPEN 状态应标记不可用")
                .isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC04  OPEN 未过期期间持续拒绝调用
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC04 OPEN 期间（openUntil 未到）：连续 3 次 allowCall 均返回 false")
    void tc04_whileOpen_repeatedCallsAllRejected() {
        triggerOpen(store, "model-d");

        for (int i = 1; i <= 3; i++) {
            assertThat(store.allowCall("model-d"))
                    .as("第 %d 次调用应被 OPEN 状态拒绝", i)
                    .isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC05  OPEN 到期 → HALF_OPEN；首次 probe=true，第二次 in-flight 保护=false
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC05 OPEN 到期后：首次 allowCall=true（HALF_OPEN probe），第二次=false（in-flight 保护）")
    void tc05_openExpired_halfOpenAllowsOneProbe() {
        // openDurationMs=-1000 → openUntil = now-1000，立即视为过期
        ModelHealthStore expiredStore = buildStore(FAILURE_THRESHOLD, -1000L);
        triggerOpen(expiredStore, "model-e");

        boolean firstProbe = expiredStore.allowCall("model-e");
        boolean secondProbe = expiredStore.allowCall("model-e");

        assertThat(firstProbe)
                .as("OPEN 过期后首次 allowCall 应进入 HALF_OPEN 并允许 probe")
                .isTrue();
        assertThat(secondProbe)
                .as("HALF_OPEN in-flight 期间第二次调用应被阻断，防止并发探测")
                .isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC06  HALF_OPEN 成功 → CLOSED，完全恢复
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC06 HALF_OPEN 探测成功：HALF_OPEN → CLOSED，allowCall=true，isUnavailable=false")
    void tc06_halfOpenSuccess_recoversToClose() {
        ModelHealthStore expiredStore = buildStore(FAILURE_THRESHOLD, -1000L);
        triggerOpen(expiredStore, "model-f");

        expiredStore.allowCall("model-f");     // OPEN → HALF_OPEN，probe 开放
        expiredStore.markSuccess("model-f");   // HALF_OPEN → CLOSED

        assertThat(expiredStore.allowCall("model-f"))
                .as("markSuccess 后应恢复正常")
                .isTrue();
        assertThat(expiredStore.isUnavailable("model-f"))
                .as("CLOSED 状态下 isUnavailable=false")
                .isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC07  HALF_OPEN 失败 → 重新 OPEN，halfOpenInFlight 重置后允许下轮探测
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC07 HALF_OPEN 探测失败：重新熔断，halfOpenInFlight 重置，下一轮 probe 可进入")
    void tc07_halfOpenFailure_reOpensAndResetsInFlight() {
        // openDurationMs=-1000：每次 OPEN 立即到期，方便观察 halfOpenInFlight 的重置行为
        ModelHealthStore expiredStore = buildStore(FAILURE_THRESHOLD, -1000L);
        triggerOpen(expiredStore, "model-g");

        // 第一轮：OPEN → HALF_OPEN（in-flight=true）
        boolean firstProbe = expiredStore.allowCall("model-g");
        // in-flight 中，第二次阻断
        boolean blockedWhileInFlight = expiredStore.allowCall("model-g");

        // 探测失败 → HALF_OPEN → OPEN（duration=-1000，openUntil 立即过期，in-flight 重置为 false）
        expiredStore.markFailure("model-g");

        // OPEN 已过期（duration=-1000），再次 allowCall 应再次允许 probe（in-flight 已被重置）
        boolean secondProbe = expiredStore.allowCall("model-g");

        assertThat(firstProbe).as("第一轮 probe 应允许").isTrue();
        assertThat(blockedWhileInFlight).as("in-flight 期间应阻断").isFalse();
        assertThat(secondProbe)
                .as("HALF_OPEN 失败后 in-flight 重置，下一轮 probe 应被允许（证明状态已正确重置）")
                .isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════
    // TC08  ModelRoutingExecutor：首选模型失败，故障转移到备用模型
    // ══════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("TC08 路由执行器：首选模型抛异常，自动故障转移到备用模型并返回响应")
    void tc08_primaryModelFails_routerFallsBackToStandby() {
        ModelTarget primary = buildTarget("model-primary");
        ModelTarget standby = buildTarget("model-standby");

        String result = executor.executeWithFallback(
                ModelCapability.CHAT,
                List.of(primary, standby),
                // clientResolver：用 modelId 作为 client 占位，确保非 null（不跳过）
                ModelTarget::id,
                (client, target) -> {
                    if ("model-primary".equals(target.id())) {
                        throw new RuntimeException("首选模型模拟宕机");
                    }
                    return "备用模型响应";
                }
        );

        assertThat(result)
                .as("故障转移后应返回备用模型的响应")
                .isEqualTo("备用模型响应");

        // 首选模型仅 1 次失败（< 阈值 2），未触发熔断，仍可被再次尝试
        assertThat(store.allowCall("model-primary"))
                .as("1 次失败未达阈值，首选模型未熔断")
                .isTrue();

        // 备用模型成功，健康状态正常
        assertThat(store.allowCall("model-standby"))
                .as("备用模型调用成功，状态应正常")
                .isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 辅助方法
    // ══════════════════════════════════════════════════════════════════════

    private static void triggerOpen(ModelHealthStore targetStore, String modelId) {
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            targetStore.markFailure(modelId);
        }
    }

    private static ModelHealthStore buildStore(int failureThreshold, long openDurationMs) {
        AIModelProperties props = new AIModelProperties();
        props.getSelection().setFailureThreshold(failureThreshold);
        props.getSelection().setOpenDurationMs(openDurationMs);
        return new ModelHealthStore(props);
    }

    private static ModelTarget buildTarget(String id) {
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setProvider("test");
        return new ModelTarget(id, candidate, new AIModelProperties.ProviderConfig());
    }
}
