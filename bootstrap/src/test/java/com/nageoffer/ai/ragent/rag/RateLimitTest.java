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

package com.nageoffer.ai.ragent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ragent 限流功能测试类
 * 模拟多个用户同时发起对话请求，测试限流和排队机制
 */
@Slf4j
public class RateLimitTest {

    private static final String BASE_URL = "http://localhost:9090/api/ragent";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    // 测试用户数据
    private static final List<TestUser> TEST_USERS = List.of(
        new TestUser("user1", "password1"),
        new TestUser("user2", "password2"),
        new TestUser("user3", "password3"),
        new TestUser("user4", "password4"),
        new TestUser("user5", "password5"),
        new TestUser("user6", "password6"),
        new TestUser("user7", "password7"),
        new TestUser("user8", "password8"),
        new TestUser("user9", "password9"),
        new TestUser("user10", "password10")
    );

    @Test
    public void testRateLimit() throws InterruptedException {
        log.info("========================================");
        log.info("🧪 Ragent 限流功能测试开始");
        log.info("========================================");

        // 步骤1: 登录所有测试用户
        log.info("\n📝 步骤 1: 登录所有测试用户...");
        List<TestUser> loggedInUsers = loginAllUsers();
        if (loggedInUsers.isEmpty()) {
            log.error("❌ 没有用户登录成功，测试终止");
            return;
        }
        log.info("✅ 登录成功的用户数: {}", loggedInUsers.size());

        // 步骤2: 3个用户并发测试
        log.info("\n========================================");
        log.info("测试 1: 3个用户并发请求");
        log.info("========================================");
        runConcurrentTest(loggedInUsers.subList(0, Math.min(3, loggedInUsers.size())), "你好，请介绍一下你自己");

        // 等待一段时间
        Thread.sleep(2000);

        // 步骤3: 10个用户并发测试
        log.info("\n========================================");
        log.info("测试 2: 10个用户并发请求");
        log.info("========================================");
        runConcurrentTest(loggedInUsers, "你好，请介绍一下你自己");

        log.info("\n========================================");
        log.info("🧪 测试完成");
        log.info("========================================");
    }

    private List<TestUser> loginAllUsers() {
        List<TestUser> loggedInUsers = new ArrayList<>();
        for (TestUser user : TEST_USERS) {
            String token = login(user.getUsername(), user.getPassword());
            if (token != null) {
                user.setToken(token);
                loggedInUsers.add(user);
                log.info("✅ 用户 {} 登录成功", user.getUsername());
            } else {
                log.warn("❌ 用户 {} 登录失败", user.getUsername());
            }
        }
        return loggedInUsers;
    }

    private String login(String username, String password) {
        try {
            String url = BASE_URL + "/auth/login";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> requestBody = Map.of(
                "username", username,
                "password", password
            );

            HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode jsonNode = objectMapper.readTree(response.getBody());
                JsonNode dataNode = jsonNode.get("data");
                if (dataNode != null && dataNode.has("token")) {
                    return dataNode.get("token").asText();
                }
            }
        } catch (Exception e) {
            log.error("用户 {} 登录异常", username, e);
        }
        return null;
    }

    private void runConcurrentTest(List<TestUser> users, String question) throws InterruptedException {
        int threadCount = users.size();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rateLimitedCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);

        log.info("🚀 开始并发测试: {} 个用户同时发起对话", threadCount);

        long startTime = System.currentTimeMillis();

        for (TestUser user : users) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 等待所有线程就绪
                    TestResult result = sendChatRequest(user, question);
                    if (result.isSuccess()) {
                        successCount.incrementAndGet();
                        log.info("✅ 用户 {} 对话请求已接受 (耗时: {}ms)", user.getUsername(), result.getResponseTime());
                    } else if (result.isRateLimited()) {
                        rateLimitedCount.incrementAndGet();
                        log.warn("⚠️ 用户 {} 触发限流/排队 (耗时: {}ms)", user.getUsername(), result.getResponseTime());
                    } else {
                        failedCount.incrementAndGet();
                        log.error("❌ 用户 {} 对话请求失败: {}", user.getUsername(), result.getError());
                    }
                } catch (Exception e) {
                    failedCount.incrementAndGet();
                    log.error("❌ 用户 {} 对话异常", user.getUsername(), e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 同时开始所有请求
        startLatch.countDown();
        endLatch.await(); // 等待所有请求完成
        executor.shutdown();

        long totalTime = System.currentTimeMillis() - startTime;

        // 输出统计结果
        log.info("\n========================================");
        log.info("📊 测试结果统计:");
        log.info("========================================");
        log.info("总请求数: {}", threadCount);
        log.info("成功数: {}", successCount.get());
        log.info("限流/排队数: {}", rateLimitedCount.get());
        log.info("失败数: {}", failedCount.get());
        log.info("总耗时: {}ms", totalTime);
        log.info("========================================");
    }

    private TestResult sendChatRequest(TestUser user, String question) {
        long startTime = System.currentTimeMillis();
        try {
            String url = BASE_URL + "/rag/v3/chat?question=" + java.net.URLEncoder.encode(question, "UTF-8");
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", user.getToken());

            HttpEntity<String> request = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);

            long responseTime = System.currentTimeMillis() - startTime;

            if (response.getStatusCode() == HttpStatus.OK) {
                return TestResult.success(responseTime);
            } else if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                return TestResult.rateLimited(responseTime);
            } else {
                return TestResult.failed("HTTP " + response.getStatusCode(), responseTime);
            }
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            return TestResult.failed(e.getMessage(), responseTime);
        }
    }

    @Data
    private static class TestUser {
        private final String username;
        private final String password;
        private String token;

        public TestUser(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    @Data
    private static class TestResult {
        private final boolean success;
        private final boolean rateLimited;
        private final String error;
        private final long responseTime;

        public static TestResult success(long responseTime) {
            return new TestResult(true, false, null, responseTime);
        }

        public static TestResult rateLimited(long responseTime) {
            return new TestResult(false, true, null, responseTime);
        }

        public static TestResult failed(String error, long responseTime) {
            return new TestResult(false, false, error, responseTime);
        }
    }
}
