package com.teriteri.backend.component.moderation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 内容审核配置（见 application.yml 的 moderation 段）
 */
@Data
@Component
@ConfigurationProperties(prefix = "moderation")
public class ModerationProperties {

    /** 敏感词词表位置（classpath 资源） */
    private String wordList = "classpath:sensitive-words.txt";

    /** LLM 异步复审通道配置 */
    private Llm llm = new Llm();

    @Data
    public static class Llm {
        /** LLM 复审通道开关；未配置 api-key 时同样自动降级为纯敏感词快筛 */
        private boolean enabled = true;
        /** DashScope API Key，留空则关闭 LLM 复审 */
        private String apiKey = "";
        /** OpenAI 兼容模式接入点 */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        /** 复审使用的模型 */
        private String model = "qwen-flash";
        /** 快筛通过内容的复审抽样率 0.0~1.0；命中 REVIEW 级的内容不受抽样率限制，必然复审 */
        private double sampleRate = 1.0;
        /** LLM 调用读超时（秒） */
        private int timeoutSeconds = 20;
    }
}
