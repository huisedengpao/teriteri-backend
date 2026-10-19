package com.teriteri.backend.component.moderation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LLM 复审通道集成测试：直连 DashScope 验证调用链路，不依赖 Spring 容器 / MySQL / Redis。
 * 未提供 API Key 时自动跳过。
 * 运行方式：
 *   mvn test -Dtest=LlmReviewClientIntegrationTest "-Ddashscope.api-key=sk-xxx"
 * 或设置环境变量 DASHSCOPE_API_KEY。
 */
class LlmReviewClientIntegrationTest {

    private static LlmReviewClient client;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getProperty("dashscope.api-key");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getenv("DASHSCOPE_API_KEY");
        }
        assumeTrue(apiKey != null && !apiKey.isEmpty(), "未配置 dashscope.api-key，跳过LLM集成测试");
        ModerationProperties properties = new ModerationProperties();
        properties.getLlm().setApiKey(apiKey);
        client = new LlmReviewClient(properties);
        assertTrue(client.isAvailable(), "LLM复审通道应处于可用状态");
    }

    @Test
    void illegalContentShouldBeRejected() {
        Optional<LlmReviewClient.Verdict> verdict = client.review("想赚钱的加微信，日结200，兼职刷单");
        assertTrue(verdict.isPresent(), "LLM调用失败或结果无法解析");
        System.out.println("[违规样本] 判定=" + (verdict.get().reject() ? "REJECT" : "PASS")
                + " 理由=" + verdict.get().reason());
        assertTrue(verdict.get().reject(), "广告引流内容应被判 REJECT");
    }

    @Test
    void normalContentShouldPass() {
        Optional<LlmReviewClient.Verdict> verdict = client.review("这个视频剪辑做得真好，学到了");
        assertTrue(verdict.isPresent(), "LLM调用失败或结果无法解析");
        System.out.println("[正常样本] 判定=" + (verdict.get().reject() ? "REJECT" : "PASS")
                + " 理由=" + verdict.get().reason());
        assertFalse(verdict.get().reject(), "正常内容应被判 PASS");
    }
}
