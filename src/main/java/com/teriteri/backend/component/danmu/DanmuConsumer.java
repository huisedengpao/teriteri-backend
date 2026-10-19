package com.teriteri.backend.component.danmu;

import com.alibaba.fastjson2.JSON;
import com.teriteri.backend.component.moderation.ContentModerationService;
import com.teriteri.backend.config.RabbitMQConfig;
import com.teriteri.backend.mapper.DanmuMapper;
import com.teriteri.backend.pojo.Danmu;
import com.teriteri.backend.service.danmu.DanmuService;
import com.teriteri.backend.service.video.VideoStatsService;
import com.teriteri.backend.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 弹幕 MQ 消费者：
 * 发送主链路只负责「鉴权 + 敏感词快筛 + 投递MQ」，
 * 这里以批量方式完成 落库 -> 统计/缓存刷新 -> 广播 -> 提交LLM复审，实现削峰填谷。
 * <p>
 * 批量粒度由 {@code danmuBatchContainerFactory} 控制：攒满 50 条或等待 200ms 投递一批。
 */
@Slf4j
@Component
public class DanmuConsumer {

    /** 弹幕分段缓存过期时间（秒） */
    private static final long SEG_CACHE_EXPIRE_SECONDS = 1800;

    @Autowired
    private DanmuMapper danmuMapper;

    @Autowired
    private VideoStatsService videoStatsService;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private ContentModerationService moderationService;

    /**
     * 批量消费弹幕（一批 = 一个批次，条数/时间窗由容器工厂控制）
     * @param batch 弹幕批次
     */
    @RabbitListener(queues = RabbitMQConfig.DANMU_QUEUE, containerFactory = "danmuBatchContainerFactory")
    public void handleBatch(List<Danmu> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        // 1. 批量写库（一次 insert 多行，替代逐条插入），回填自增主键
        try {
            danmuMapper.insertBatch(batch);
        } catch (Exception e) {
            // 弹幕属可容忍丢失的业务：落库失败记录日志并丢弃该批，
            // 避免毒消息无限重入队造成队列积压
            log.error("弹幕批量落库失败，丢弃该批 count={}", batch.size(), e);
            return;
        }

        // 2. 按视频分组，批量刷新统计与缓存
        Map<Integer, List<Danmu>> byVid = new HashMap<>();
        for (Danmu danmu : batch) {
            byVid.computeIfAbsent(danmu.getVid(), k -> new ArrayList<>()).add(danmu);
        }
        for (Map.Entry<Integer, List<Danmu>> entry : byVid.entrySet()) {
            Integer vid = entry.getKey();
            List<Danmu> list = entry.getValue();
            try {
                // 弹幕计数一次性 +N
                videoStatsService.updateStats(vid, "danmu", true, list.size());
                // 弹幕ID集合（弹幕列表查询用）
                List<Object> ids = new ArrayList<>(list.size());
                for (Danmu danmu : list) {
                    ids.add(danmu.getId());
                }
                redisUtil.addMembers("danmu_idset:" + vid, ids);
                // 分段缓存：zset 以弹幕时间点为分数、弹幕ID为成员，支持按播放进度分段拉取
                String segKey = DanmuService.SEG_CACHE_KEY_PREFIX + vid;
                for (Danmu danmu : list) {
                    redisUtil.zAdd(segKey, danmu.getId(), danmu.getTimePoint());
                }
                redisUtil.setExpire(segKey, SEG_CACHE_EXPIRE_SECONDS);
            } catch (Exception e) {
                log.error("刷新视频弹幕缓存失败 vid={}", vid, e);
            }
        }

        // 3. 批量广播（保持单条弹幕JSON格式，兼容前端）
        for (Danmu danmu : batch) {
            DanmuWebSocketServer.broadcast(String.valueOf(danmu.getVid()), JSON.toJSONString(danmu));
        }

        // 4. 提交LLM异步复审，判定违规将软删除并广播撤回
        for (Danmu danmu : batch) {
            moderationService.submitAsyncReview(ContentModerationService.TYPE_DANMU,
                    danmu.getId(), danmu.getVid(), danmu.getContent());
        }
    }
}
