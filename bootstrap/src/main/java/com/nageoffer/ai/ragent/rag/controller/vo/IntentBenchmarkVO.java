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
 * 意图树缓存对比基准测试结果
 */
@Data
@Builder
public class IntentBenchmarkVO {

    private Group withCache;
    private Group withoutCache;
    /** 差值倍数：无缓存耗时 / 有缓存耗时 */
    private String speedupRatio;
    /** 每轮平均节省的毫秒数 */
    private long savedAvgMs;

    @Data
    @Builder
    public static class Group {
        /** redis_cache 或 database */
        private String source;
        private int rounds;
        private int nodeCount;
        private int leafCount;
        private List<Long> roundMs;
        private long avgMs;
        private long minMs;
        private long maxMs;
        private long p90Ms;
    }
}
