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

import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 模型路由执行器
 * 负责在多个模型候选者之间进行调度执行，并提供故障转移（Fallback）和健康检查机制
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRoutingExecutor {

    private final ModelHealthStore healthStore;
    private final ModelConcurrencyStore concurrencyStore;

    public <C, T> T executeWithFallback(
            ModelCapability capability,
            List<ModelTarget> targets,
            Function<ModelTarget, C> clientResolver,
            ModelCaller<C, T> caller) {
        String label = capability.getDisplayName();
        if (targets == null || targets.isEmpty()) {
            throw new RemoteException("No " + label + " model candidates available");
        }

        Throwable last = null;
        List<ModelTarget> capacityDeferred = new ArrayList<>();

        // 第一轮：只调用有容量的模型
        for (ModelTarget target : targets) {
            C client = clientResolver.apply(target);
            if (client == null) {
                log.warn("{} provider client missing: provider={}, modelId={}", label, target.candidate().getProvider(), target.id());
                continue;
            }
            if (!healthStore.allowCall(target.id())) {
                continue;
            }
            int maxConcurrent = resolveMaxConcurrent(target);
            if (!concurrencyStore.tryAcquire(target.id(), maxConcurrent)) {
                capacityDeferred.add(target);
                continue;
            }
            try {
                T response = caller.call(client, target);
                healthStore.markSuccess(target.id());
                return response;
            } catch (Exception e) {
                last = e;
                healthStore.markFailure(target.id());
                log.warn("{} model failed, fallback to next. modelId={}, provider={}", label, target.id(), target.candidate().getProvider(), e);
            } finally {
                concurrencyStore.release(target.id(), maxConcurrent);
            }
        }

        // 第二轮：所有候选容量均满时强制尝试（让 LLM 侧的 429 走正常故障转移）
        for (ModelTarget target : capacityDeferred) {
            C client = clientResolver.apply(target);
            if (client == null) {
                continue;
            }
            if (!healthStore.allowCall(target.id())) {
                continue;
            }
            int maxConcurrent = resolveMaxConcurrent(target);
            concurrencyStore.forceAcquire(target.id(), maxConcurrent);
            try {
                T response = caller.call(client, target);
                healthStore.markSuccess(target.id());
                return response;
            } catch (Exception e) {
                last = e;
                healthStore.markFailure(target.id());
                log.warn("{} model failed (capacity-fallback), fallback to next. modelId={}, provider={}", label, target.id(), target.candidate().getProvider(), e);
            } finally {
                concurrencyStore.release(target.id(), maxConcurrent);
            }
        }

        throw new RemoteException(
                "All " + label + " model candidates failed: " + (last == null ? "unknown" : last.getMessage()),
                last,
                BaseErrorCode.REMOTE_ERROR
        );
    }

    private static int resolveMaxConcurrent(ModelTarget target) {
        Integer max = target.candidate().getMaxConcurrent();
        return max != null ? max : 0;
    }
}
