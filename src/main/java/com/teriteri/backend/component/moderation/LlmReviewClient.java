package com.teriteri.backend.component.moderation;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM 内容复审客户端，对接 DashScope 的 OpenAI 兼容接口。
 * 仅用于异步复审通道，任何异常都 fail-open（记录日志并放行），绝不影响发送主链路。
 */
@Slf4j
@Component
public class LlmReviewClient {

    /** 复审判定结果 */
    public record Verdict(boolean reject, String reason) {}

    private static final String SYSTEM_PROMPT = """
            你是一个视频网站的内容审核专家。请判断用户提交的文本是否包含以下违规内容：
            1. 人身攻击、辱骂、恶意谩骂
            2. 暴力、威胁、恐吓
            3. 色情低俗
            4. 赌博、毒品等违法违规内容
            5. 虚假广告、恶意引流（如诱导加微信/QQ、兼职刷单等）
            请严格以 JSON 格式输出：{"verdict":"PASS"或"REJECT","reason":"简短理由"}
            只输出 JSON，不要输出任何其他解释文字。""";

    private final ModerationProperties properties;
    private final RestClient restClient;

    @Autowired
    public LlmReviewClient(ModerationProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(properties.getLlm().getTimeoutSeconds()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** LLM 复审通道是否可用（开关关闭或未配置 Key 时自动降级） */
    public boolean isAvailable() {
        return properties.getLlm().isEnabled() && StringUtils.hasText(properties.getLlm().getApiKey());
    }

    /** 429 限流时的最大尝试次数 */
    private static final int MAX_ATTEMPTS = 4;

    /**
     * 调用 LLM 复审一段内容。
     * 遇到 429 限流按指数退避重试：等待 2s、4s、8s（弹幕高峰期可能瞬时超出模型 RPM 配额）；
     * 其他异常直接 fail-open。
     * @return 判定结果；调用失败或结果无法解析时返回 empty（fail-open）
     */
    public Optional<Verdict> review(String content) {
        Map<String, Object> body = Map.of(
                "model", properties.getLlm().getModel(),
                "temperature", 0.0,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", content)
                )
        );
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String resp = restClient.post()
                        .uri(properties.getLlm().getBaseUrl() + "/chat/completions")
                        .header("Authorization", "Bearer " + properties.getLlm().getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return parse(resp);
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt < MAX_ATTEMPTS) {
                    // 指数退避：2s -> 4s -> 8s
                    long backoffMillis = 2000L << (attempt - 1);
                    log.warn("LLM 复审触发限流(429)，{}ms 后进行第 {} 次重试", backoffMillis, attempt);
                    try {
                        Thread.sleep(backoffMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return Optional.empty();
                    }
                    continue;
                }
                log.error("LLM 复审限流重试 {} 次后仍失败，内容长度={}", MAX_ATTEMPTS,
                        content == null ? 0 : content.length());
                return Optional.empty();
            } catch (Exception e) {
                log.error("LLM 复审调用失败，内容长度={}", content == null ? 0 : content.length(), e);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Optional<Verdict> parse(String resp) {
        try {
            JSONObject json = JSON.parseObject(resp);
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return Optional.empty();
            }
            String text = choices.getJSONObject(0).getJSONObject("message").getString("content");
            if (text == null) {
                return Optional.empty();
            }
            // 模型可能把 JSON 包在 markdown 代码块里，取第一个 JSON 对象
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return Optional.empty();
            }
            JSONObject verdict = JSON.parseObject(text.substring(start, end + 1));
            String v = verdict.getString("verdict");
            if (v == null) {
                return Optional.empty();
            }
            return Optional.of(new Verdict("REJECT".equalsIgnoreCase(v.trim()), verdict.getString("reason")));
        } catch (Exception e) {
            log.error("解析 LLM 复审结果失败: {}", resp, e);
            return Optional.empty();
        }
    }
}
