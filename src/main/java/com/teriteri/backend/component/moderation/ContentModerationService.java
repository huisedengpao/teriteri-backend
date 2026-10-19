package com.teriteri.backend.component.moderation;

import com.teriteri.backend.component.danmu.DanmuWebSocketServer;
import com.teriteri.backend.mapper.DanmuMapper;
import com.teriteri.backend.pojo.Danmu;
import com.teriteri.backend.service.comment.CommentService;
import com.teriteri.backend.service.danmu.DanmuService;
import com.teriteri.backend.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 内容审核服务：敏感词 AC 自动机快筛（同步） + LLM 复审（异步）双通道。
 * <ul>
 *   <li>快筛通道：{@link SensitiveWordFilter}，O(n) 微秒级，命中 BLOCK 级直接拒绝，命中 REVIEW 级先放行</li>
 *   <li>复审通道：{@link LlmReviewClient}，线程池异步执行，判定违规后执行对应处置（软删除/撤回）</li>
 * </ul>
 * 复审通道整体 fail-open：LLM 未配置、调用失败、解析失败都不影响发送主链路。
 */
@Slf4j
@Service
public class ContentModerationService {

    public static final String TYPE_DANMU = "danmu";
    public static final String TYPE_COMMENT = "comment";
    public static final String TYPE_VIDEO = "video";

    @Autowired
    private SensitiveWordFilter sensitiveWordFilter;

    @Autowired
    private LlmReviewClient llmReviewClient;

    @Autowired
    private ModerationProperties properties;

    @Autowired
    private DanmuMapper danmuMapper;

    @Autowired
    private CommentService commentService;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;

    /**
     * 同步快筛（AC 自动机），供发送链路在写入前调用
     */
    public ModerationResult fastCheck(String content) {
        return sensitiveWordFilter.check(content);
    }

    /**
     * 提交 LLM 异步复审。
     * 命中 REVIEW 级的内容必然复审；快筛通过的内容按抽样率决定（控制 LLM 调用成本）。
     * 通道不可用（未配置 API Key 等）时直接跳过，静默降级为纯敏感词模式。
     *
     * @param type       内容类型，见 TYPE_* 常量
     * @param id         内容主键（弹幕/评论/视频 ID）
     * @param vid        关联视频 ID（弹幕撤回广播用，其他类型可与 id 相同或为 null）
     * @param content    待复审文本
     * @param fastResult 快筛结果（避免重复扫描）
     */
    public void submitAsyncReview(String type, Integer id, Integer vid, String content, ModerationResult fastResult) {
        if (content == null || content.isEmpty() || !llmReviewClient.isAvailable()) {
            return;
        }
        boolean sampled = fastResult.needReview()
                || ThreadLocalRandom.current().nextDouble() < properties.getLlm().getSampleRate();
        if (!sampled) {
            return;
        }
        CompletableFuture.runAsync(() -> doLlmReview(type, id, vid, content), taskExecutor);
    }

    /** 便捷方法：内部先快筛再决定是否复审 */
    public void submitAsyncReview(String type, Integer id, Integer vid, String content) {
        submitAsyncReview(type, id, vid, content, fastCheck(content));
    }

    private void doLlmReview(String type, Integer id, Integer vid, String content) {
        try {
            Optional<LlmReviewClient.Verdict> result = llmReviewClient.review(content);
            if (result.isEmpty()) {
                return;
            }
            LlmReviewClient.Verdict verdict = result.get();
            if (!verdict.reject()) {
                log.debug("LLM 复审通过 type={} id={}", type, id);
                return;
            }
            log.warn("LLM 复审判定违规 type={} id={} reason={}", type, id, verdict.reason());
            switch (type) {
                case TYPE_DANMU -> rejectDanmu(id, vid);
                case TYPE_COMMENT -> commentService.deleteComment(id, 0, true);
                case TYPE_VIDEO -> saveVideoVerdict(id, content, verdict);
                default -> { }
            }
        } catch (Exception e) {
            log.error("LLM 复审处置异常 type={} id={}", type, id, e);
        }
    }

    /**
     * 弹幕违规处置：软删除（state=3）+ 移出弹幕 ID 缓存集合 + 向该视频所有在线客户端广播撤回
     */
    private void rejectDanmu(Integer id, Integer vid) {
        Danmu update = new Danmu();
        update.setId(id);
        update.setState(3);
        danmuMapper.updateById(update);
        if (vid == null) {
            return;
        }
        redisUtil.delMember("danmu_idset:" + vid, id);
        redisUtil.zRem(DanmuService.SEG_CACHE_KEY_PREFIX + vid, id);
        DanmuWebSocketServer.broadcast(String.valueOf(vid), "{\"type\":\"recall\",\"id\":" + id + "}");
    }

    /**
     * 视频复审判定：写入 Redis 供管理员人工审核参考（视频本已有人工审核流程，AI 不自动拦截）
     */
    private void saveVideoVerdict(Integer vid, String content, LlmReviewClient.Verdict verdict) {
        Map<String, Object> suggestion = new HashMap<>();
        suggestion.put("vid", vid);
        suggestion.put("verdict", verdict.reject() ? "REJECT" : "PASS");
        suggestion.put("reason", verdict.reason());
        suggestion.put("content", content);
        suggestion.put("time", System.currentTimeMillis());
        redisUtil.setExObjectValue("ai_review:video:" + vid, suggestion, 7, TimeUnit.DAYS);
    }
}
