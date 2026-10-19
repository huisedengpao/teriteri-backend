package com.teriteri.backend.component.danmu;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.teriteri.backend.component.moderation.ContentModerationService;
import com.teriteri.backend.component.moderation.ModerationResult;
import com.teriteri.backend.config.RabbitMQConfig;
import com.teriteri.backend.pojo.Danmu;
import com.teriteri.backend.pojo.User;
import com.teriteri.backend.utils.JwtUtil;
import com.teriteri.backend.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@ServerEndpoint(value = "/ws/danmu/{vid}")
public class DanmuWebSocketServer {

    // 由于每个连接都不是共享一个WebSocketServer，所以要静态注入
    private static JwtUtil jwtUtil;
    private static RedisUtil redisUtil;
    private static ContentModerationService moderationService;
    private static RabbitTemplate rabbitTemplate;

    @Autowired
    public void setDependencies(JwtUtil jwtUtil, RedisUtil redisUtil,
                                ContentModerationService moderationService, RabbitTemplate rabbitTemplate) {
        DanmuWebSocketServer.jwtUtil = jwtUtil;
        DanmuWebSocketServer.redisUtil = redisUtil;
        DanmuWebSocketServer.moderationService = moderationService;
        DanmuWebSocketServer.rabbitTemplate = rabbitTemplate;
    }

    // 对每个视频存储该视频下的session集合（值使用线程安全集合，避免并发收发弹幕时集合修改异常）
    private static final Map<String, Set<Session>> videoConnectionMap = new ConcurrentHashMap<>();

    /**
     * 连接建立时触发，记录session到map
     * @param session 会话
     * @param vid   视频的ID
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("vid") String vid) {
        Set<Session> set = videoConnectionMap.computeIfAbsent(vid, k -> ConcurrentHashMap.newKeySet());
        set.add(session);
        sendMessage(vid, "当前观看人数" + set.size());
    }

    /**
     * 收到消息时触发：鉴权 + 敏感词快筛通过后投递 MQ，
     * 写库、缓存刷新与广播全部移交消费端批量执行（削峰），发送主链路不再被数据库拖慢
     * @param session   当前会话
     * @param message   信息体（包含"token"、"vid"、"data"字段）
     * @param vid   视频ID
     */
    @OnMessage
    public void onMessage(Session session, String message, @PathParam("vid") String vid) {
        try {
            JSONObject msg = JSON.parseObject(message);

            // token鉴权
            String token = msg.getString("token");
            if (!StringUtils.hasText(token) || !token.startsWith("Bearer ")) {
                session.getBasicRemote().sendText("登录已过期");
                return;
            }
            token = token.substring(7);
            boolean verifyToken = jwtUtil.verifyToken(token);
            if (!verifyToken) {
                session.getBasicRemote().sendText("登录已过期");
                return;
            }
            String userId = JwtUtil.getSubjectFromToken(token);
            String role = JwtUtil.getClaimFromToken(token, "role");
            User user = redisUtil.getObject("security:" + role + ":" + userId, User.class);
            if (user == null) {
                session.getBasicRemote().sendText("登录已过期");
                return;
            }

            JSONObject data = msg.getJSONObject("data");
            String content = data.getString("content");

            // 内容审核双通道：AC自动机同步快筛，命中拦截级敏感词直接拒绝；复审级先放行，落库后由LLM异步复审
            ModerationResult moderationResult = moderationService.fastCheck(content);
            if (moderationResult.isBlock()) {
                session.getBasicRemote().sendText("弹幕含敏感内容，发送失败");
                return;
            }

            // 构建弹幕（主键由消费端批量插入后回填），投递 MQ 削峰
            Danmu danmu = new Danmu(
                    null,
                    Integer.parseInt(vid),
                    user.getUid(),
                    content,
                    data.getInteger("fontsize"),
                    data.getInteger("mode"),
                    data.getString("color"),
                    data.getDouble("timePoint"),
                    1,
                    new Date()
            );
            try {
                rabbitTemplate.convertAndSend(RabbitMQConfig.DANMU_EXCHANGE, RabbitMQConfig.DANMU_ROUTING_KEY, danmu);
            } catch (Exception e) {
                // MQ 不可用时快速失败并提示，不阻塞连接
                log.error("弹幕投递MQ失败 vid={}", vid, e);
                session.getBasicRemote().sendText("弹幕发送失败，请稍后重试");
            }
        } catch (Exception e) {
            log.error("处理弹幕消息失败 vid={}", vid, e);
        }
    }

    /**
     * 连接关闭时执行
     * @param session   当前会话
     * @param vid   视频ID
     */
    @OnClose
    public void onClose(Session session, @PathParam("vid") String vid) {
        // 从缓存中移除连接记录
        Set<Session> set = videoConnectionMap.get(vid);
        if (set == null) {
            return;
        }
        set.remove(session);
        if (set.isEmpty()) {
            // 如果没人了就直接移除这个视频（带值比较，避免误删并发新建的集合）
            videoConnectionMap.remove(vid, set);
        } else {
            // 否则更新在线人数
            sendMessage(vid, "当前观看人数" + set.size());
        }
    }

    @OnError
    public void onError(Throwable error) {
        log.error("websocket发生错误");
        error.printStackTrace();
    }

    /**
     * 往对应的全部连接发送消息
     * @param vid   视频ID
     * @param text  消息内容，对象需转成JSON字符串
     */
    public void sendMessage(String vid, String text) {
        broadcast(vid, text);
    }

    /**
     * 向指定视频的全部连接广播消息（静态方法，供审核撤回、MQ消费端等外部组件调用）
     * @param vid   视频ID
     * @param text  消息内容
     */
    public static void broadcast(String vid, String text) {
        Set<Session> set = videoConnectionMap.get(vid);
        if (set == null || set.isEmpty()) {
            return;
        }
        // 先取快照，避免广播期间连接并发增减导致 ConcurrentModificationException
        Set<Session> snapshot = new HashSet<>(set);
        for (Session session : snapshot) {
            try {
                if (session.isOpen()) {
                    session.getBasicRemote().sendText(text);
                }
            } catch (Exception e) {
                log.error("广播消息到视频 {} 失败", vid, e);
            }
        }
    }
}
