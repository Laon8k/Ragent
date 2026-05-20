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

package com.nageoffer.ai.ragent.rag.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 上下文压缩效果对比结果
 */
@Data
@Builder
public class MemoryContextVO {

    /** 总对话轮数（仅 user 消息） */
    private long totalTurns;

    /** 历史保留窗口（historyKeepTurns） */
    private int historyKeepTurns;

    /** 摘要功能是否启用 */
    private boolean summaryEnabled;

    /** 摘要内容（null 表示尚未生成） */
    private String summaryContent;

    /** 有压缩：LLM 实际看到的消息（摘要 + 近 N 轮） */
    private List<MessageItem> withCompression;

    /** 无压缩：LLM 实际看到的消息（仅近 N 轮，无摘要） */
    private List<MessageItem> withoutCompression;

    @Data
    @Builder
    public static class MessageItem {
        private String role;
        private String content;
    }
}
