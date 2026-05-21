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

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.MemoryContextVO;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemorySummaryService;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 上下文压缩效果对比服务
 * <p>
 * 对同一会话分别查询「有摘要」和「无摘要」时 LLM 看到的上下文，
 * 用于直观展示上下文压缩的实际效果。
 */
@Service
@RequiredArgsConstructor
public class MemoryCompressionService {

    private final ConversationMemoryService conversationMemoryService;
    private final ConversationMemorySummaryService summaryService;
    private final ConversationMemoryStore memoryStore;
    private final ConversationGroupService conversationGroupService;
    private final MemoryProperties memoryProperties;

    public MemoryContextVO compare(String conversationId, String userId) {
        // 总轮数
        long totalTurns = conversationGroupService.countUserMessages(conversationId, userId);

        // 摘要内容
        ChatMessage rawSummary = summaryService.loadLatestSummary(conversationId, userId);
        String summaryContent = rawSummary != null ? rawSummary.getContent() : null;

        // 有压缩：走完整 memory load 流程（包含摘要 + 近 N 轮历史）
        List<ChatMessage> withComp = conversationMemoryService.load(conversationId, userId);

        // 无压缩：只取近 N 轮历史，不拼摘要
        List<ChatMessage> withoutComp = memoryStore.loadHistory(conversationId, userId);

        return MemoryContextVO.builder()
                .totalTurns(totalTurns)
                .historyKeepTurns(memoryProperties.getHistoryKeepTurns())
                .summaryEnabled(Boolean.TRUE.equals(memoryProperties.getSummaryEnabled()))
                .summaryContent(summaryContent)
                .withCompression(toItems(withComp))
                .withoutCompression(toItems(withoutComp))
                .build();
    }

    private List<MemoryContextVO.MessageItem> toItems(List<ChatMessage> messages) {
        if (CollUtil.isEmpty(messages)) {
            return List.of();
        }
        return messages.stream()
                .map(m -> MemoryContextVO.MessageItem.builder()
                        .role(m.getRole().name().toLowerCase())
                        .content(m.getContent())
                        .build())
                .collect(Collectors.toList());
    }
}
