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

package com.nageoffer.ai.ragent.infra.chat;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.model.ModelConcurrencyStore;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式 LLM 服务实现类
 */
@Slf4j
@Service
@Primary
public class RoutingLLMService implements LLMService {

    private static final int FIRST_PACKET_TIMEOUT_SECONDS = 60;
    private static final String STREAM_INTERRUPTED_MESSAGE = "流式请求被中断";
    private static final String STREAM_NO_PROVIDER_MESSAGE = "无可用大模型提供者";
    private static final String STREAM_START_FAILED_MESSAGE = "流式请求启动失败";
    private static final String STREAM_TIMEOUT_MESSAGE = "流式首包超时";
    private static final String STREAM_NO_CONTENT_MESSAGE = "流式请求未返回内容";
    private static final String STREAM_ALL_FAILED_MESSAGE = "大模型调用失败，请稍后再试...";

    private final ModelSelector selector;
    private final ModelHealthStore healthStore;
    private final ModelRoutingExecutor executor;
    private final ModelConcurrencyStore concurrencyStore;
    private final Map<String, ChatClient> clientsByProvider;

    public RoutingLLMService(
            ModelSelector selector,
            ModelHealthStore healthStore,
            ModelRoutingExecutor executor,
            ModelConcurrencyStore concurrencyStore,
            List<ChatClient> clients) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.executor = executor;
        this.concurrencyStore = concurrencyStore;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(ChatClient::provider, Function.identity()));
    }

    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public String chat(ChatRequest request) {
        return executor.executeWithFallback(
                ModelCapability.CHAT,
                selector.selectChatCandidates(Boolean.TRUE.equals(request.getThinking())),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.chat(request, target)
        );
    }

    @Override
    public String chat(ChatRequest request, String modelId) {
        if (!StringUtils.hasText(modelId)) {
            return chat(request);
        }
        return executor.executeWithFallback(
                ModelCapability.CHAT,
                List.of(resolveTarget(modelId, Boolean.TRUE.equals(request.getThinking()))),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.chat(request, target)
        );
    }

    @Override
    @RagTraceNode(name = "llm-stream-routing", type = "LLM_ROUTING")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
        List<ModelTarget> targets = selector.selectChatCandidates(Boolean.TRUE.equals(request.getThinking()));
        if (CollUtil.isEmpty(targets)) {
            throw new RemoteException(STREAM_NO_PROVIDER_MESSAGE);
        }

        String label = ModelCapability.CHAT.getDisplayName();
        Throwable lastError = null;
        List<ModelTarget> capacityDeferred = new ArrayList<>();

        // 第一轮：只调用有容量的模型
        for (ModelTarget target : targets) {
            ChatClient client = resolveClient(target, label);
            if (client == null) {
                continue;
            }
            if (!healthStore.allowCall(target.id())) {
                continue;
            }
            int maxConcurrent = resolveMaxConcurrent(target);
            if (!concurrencyStore.tryAcquire(target.id(), maxConcurrent)) {
                log.debug("{} model at capacity, deferring. modelId={}", label, target.id());
                capacityDeferred.add(target);
                continue;
            }
            StreamCancellationHandle handle = tryStream(request, callback, target, maxConcurrent, label, false);
            if (handle != null) {
                return handle;
            }
            lastError = LAST_ERROR_SENTINEL;
        }

        // 第二轮：所有候选均满时强制尝试
        for (ModelTarget target : capacityDeferred) {
            ChatClient client = resolveClient(target, label);
            if (client == null) {
                continue;
            }
            if (!healthStore.allowCall(target.id())) {
                continue;
            }
            int maxConcurrent = resolveMaxConcurrent(target);
            StreamCancellationHandle handle = tryStream(request, callback, target, maxConcurrent, label, true);
            if (handle != null) {
                return handle;
            }
            lastError = LAST_ERROR_SENTINEL;
        }

        throw notifyAllFailed(callback, lastError);
    }

    /**
     * 尝试对单个 target 发起流式调用。
     * force=true 时跳过容量检查直接强制占位（兜底路径）。
     * 返回非 null 的 handle 表示成功，null 表示失败需继续尝试下一个。
     */
    private StreamCancellationHandle tryStream(ChatRequest request, StreamCallback callback,
                                               ModelTarget target, int maxConcurrent,
                                               String label, boolean force) {
        if (force) {
            concurrencyStore.forceAcquire(target.id(), maxConcurrent);
        }

        // 流结束时（onComplete/onError）自动释放槽位
        StreamCallback releasing = wrapWithRelease(callback, target.id(), maxConcurrent);
        ProbeStreamBridge bridge = new ProbeStreamBridge(releasing);

        boolean slotHeld = false;
        try {
            StreamCancellationHandle handle;
            try {
                handle = clientsByProvider.get(target.candidate().getProvider())
                        .streamChat(request, bridge, target);
            } catch (Exception e) {
                healthStore.markFailure(target.id());
                log.warn("{} 流式请求启动失败，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider(), e);
                return null;
            }
            if (handle == null) {
                healthStore.markFailure(target.id());
                log.warn("{} 流式请求未返回取消句柄，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider());
                return null;
            }

            ProbeStreamBridge.ProbeResult result = awaitFirstPacket(bridge, handle, callback);

            if (result.isSuccess()) {
                healthStore.markSuccess(target.id());
                slotHeld = true; // 槽位由 releasing 包装器在流结束时释放
                return handle;
            }

            healthStore.markFailure(target.id());
            handle.cancel();
            buildLastErrorAndLog(result, target, label);
            return null;

        } finally {
            // slotHeld=true 说明流已成功启动，槽位由 releasing 包装器负责释放；否则在此兜底
            if (!slotHeld) {
                concurrencyStore.release(target.id(), maxConcurrent);
            }
        }
    }

    private StreamCallback wrapWithRelease(StreamCallback delegate, String modelId, int maxConcurrent) {
        return new StreamCallback() {
            @Override
            public void onContent(String content) {
                delegate.onContent(content);
            }

            @Override
            public void onThinking(String content) {
                delegate.onThinking(content);
            }

            @Override
            public void onComplete() {
                try {
                    delegate.onComplete();
                } finally {
                    concurrencyStore.release(modelId, maxConcurrent);
                }
            }

            @Override
            public void onError(Throwable error) {
                try {
                    delegate.onError(error);
                } finally {
                    concurrencyStore.release(modelId, maxConcurrent);
                }
            }
        };
    }

    private static int resolveMaxConcurrent(ModelTarget target) {
        Integer max = target.candidate().getMaxConcurrent();
        return max != null ? max : 0;
    }

    private static final Throwable LAST_ERROR_SENTINEL = new RuntimeException("model routing failed");

    private ChatClient resolveClient(ModelTarget target, String label) {
        ChatClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            log.warn("{} 提供商客户端缺失: provider：{}，modelId：{}",
                    label, target.candidate().getProvider(), target.id());
        }
        return client;
    }

    private ProbeStreamBridge.ProbeResult awaitFirstPacket(ProbeStreamBridge bridge,
                                                           StreamCancellationHandle handle,
                                                           StreamCallback callback) {
        try {
            return bridge.awaitFirstPacket(FIRST_PACKET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.cancel();
            RemoteException interruptedException = new RemoteException(STREAM_INTERRUPTED_MESSAGE, e, BaseErrorCode.REMOTE_ERROR);
            callback.onError(interruptedException);
            throw interruptedException;
        }
    }

    private Throwable buildLastErrorAndLog(ProbeStreamBridge.ProbeResult result, ModelTarget target, String label) {
        switch (result.getType()) {
            case ERROR -> {
                Throwable error = result.getError() != null
                        ? result.getError()
                        : new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败，切换下一个模型",
                        label, target.id(), target.candidate().getProvider(), error);
                return error;
            }
            case TIMEOUT -> {
                RemoteException timeout = new RemoteException(STREAM_TIMEOUT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求超时，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return timeout;
            }
            case NO_CONTENT -> {
                RemoteException noContent = new RemoteException(STREAM_NO_CONTENT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求无内容完成，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return noContent;
            }
            default -> {
                RemoteException unknown = new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败（未知类型），切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return unknown;
            }
        }
    }

    private RemoteException notifyAllFailed(StreamCallback callback, Throwable lastError) {
        RemoteException finalException = new RemoteException(
                STREAM_ALL_FAILED_MESSAGE,
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
        callback.onError(finalException);
        return finalException;
    }

    private ModelTarget resolveTarget(String modelId, boolean deepThinking) {
        return selector.selectChatCandidates(deepThinking).stream()
                .filter(target -> modelId.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new RemoteException("Chat 模型不可用: " + modelId));
    }
}
