package com.teriteri.backend.service.impl.danmu;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.teriteri.backend.mapper.DanmuMapper;
import com.teriteri.backend.mapper.VideoMapper;
import com.teriteri.backend.pojo.CustomResponse;
import com.teriteri.backend.pojo.Danmu;
import com.teriteri.backend.pojo.Video;
import com.teriteri.backend.service.danmu.DanmuService;
import com.teriteri.backend.service.video.VideoStatsService;
import com.teriteri.backend.utils.RedisUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class DanmuServiceImpl implements DanmuService {

    /** 弹幕分段缓存过期时间（秒） */
    private static final long SEG_CACHE_EXPIRE_SECONDS = 1800;

    @Autowired
    private DanmuMapper danmuMapper;

    @Autowired
    private VideoMapper videoMapper;

    @Autowired
    private VideoStatsService videoStatsService;

    @Autowired
    private RedisUtil redisUtil;

    /**
     * 根据弹幕ID集合查询弹幕列表
     * @param idset 弹幕ID集合
     * @return  弹幕列表
     */
    @Override
    public List<Danmu> getDanmuListByIdset(Set<Object> idset) {
        if (idset == null || idset.size() == 0) {
            return null;
        }
        QueryWrapper<Danmu> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("id", idset).eq("state", 1);
        return danmuMapper.selectList(queryWrapper);
    }

    /**
     * 按视频时间点范围查询弹幕（分段缓存）：
     * 播放器按播放进度分段拉取，避免一次性加载全量弹幕；
     * 缓存未命中时从库重建该视频的分段缓存后再取
     */
    @Override
    public List<Danmu> getDanmuListByTimeRange(Integer vid, double start, double end) {
        String segKey = DanmuService.SEG_CACHE_KEY_PREFIX + vid;
        Set<Object> ids = redisUtil.zRangeByScore(segKey, start, end);
        if (ids == null || ids.isEmpty()) {
            // 仅当分段缓存整体不存在时才重建，避免「该段本就无弹幕」时反复查库
            if (!redisUtil.isExist(segKey) && rebuildSegCache(vid, segKey)) {
                ids = redisUtil.zRangeByScore(segKey, start, end);
            }
            if (ids == null || ids.isEmpty()) {
                return Collections.emptyList();
            }
        }
        return getDanmuListByIdset(ids);
    }

    /**
     * 从库重建指定视频的弹幕分段缓存（zset：分数=时间点，成员=弹幕ID）
     * @return  是否重建出了有效缓存
     */
    private boolean rebuildSegCache(Integer vid, String segKey) {
        QueryWrapper<Danmu> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("vid", vid).eq("state", 1);
        List<Danmu> all = danmuMapper.selectList(queryWrapper);
        if (all == null || all.isEmpty()) {
            return false;
        }
        for (Danmu danmu : all) {
            redisUtil.zAdd(segKey, danmu.getId(), danmu.getTimePoint());
        }
        redisUtil.setExpire(segKey, SEG_CACHE_EXPIRE_SECONDS);
        return true;
    }

    @Override
    @Transactional
    public CustomResponse deleteDanmu(Integer id, Integer uid, boolean isAdmin) {
        CustomResponse customResponse = new CustomResponse();
        QueryWrapper<Danmu> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("id", id).ne("state", 3);
        Danmu danmu = danmuMapper.selectOne(queryWrapper);
        if (danmu == null) {
            customResponse.setCode(404);
            customResponse.setMessage("弹幕不存在");
            return customResponse;
        }
        // 判断该用户是否有权限删除这条评论
        Video video = videoMapper.selectById(danmu.getVid());
        if (Objects.equals(danmu.getUid(), uid) || isAdmin || Objects.equals(video.getUid(), uid)) {
            // 删除弹幕
            UpdateWrapper<Danmu> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", id).set("state", 3);
            danmuMapper.update(null, updateWrapper);
            videoStatsService.updateStats(danmu.getVid(), "danmu", false, 1);
            redisUtil.delMember("danmu_idset:" + danmu.getVid(), id);
            redisUtil.zRem(DanmuService.SEG_CACHE_KEY_PREFIX + danmu.getVid(), id);
        } else {
            customResponse.setCode(403);
            customResponse.setMessage("你无权删除该条评论");
        }
        return customResponse;
    }
}
