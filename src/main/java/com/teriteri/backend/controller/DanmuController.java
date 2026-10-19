package com.teriteri.backend.controller;

import com.teriteri.backend.pojo.CustomResponse;
import com.teriteri.backend.pojo.Danmu;
import com.teriteri.backend.service.danmu.DanmuService;
import com.teriteri.backend.service.utils.CurrentUser;
import com.teriteri.backend.utils.RedisUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
public class DanmuController {
    @Autowired
    private DanmuService danmuService;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private CurrentUser currentUser;

    /**
     * 获取弹幕列表
     * @param vid   视频ID
     * @return  CustomResponse对象
     */
    @GetMapping("/danmu-list/{vid}")
    public CustomResponse getDanmuList(@PathVariable("vid") String vid) {
        Set<Object> idset = redisUtil.getMembers("danmu_idset:" + vid);
        List<Danmu> danmuList = danmuService.getDanmuListByIdset(idset);
        CustomResponse customResponse = new CustomResponse();
        customResponse.setData(danmuList);
        return customResponse;
    }

    /**
     * 按时间点范围获取弹幕（分段缓存）
     * 播放器可按播放进度分段拉取，如 start=0&end=60 拉取第0~60秒的弹幕，避免一次性加载全量弹幕
     * @param vid   视频ID
     * @param start 起始时间点（秒，含）
     * @param end   结束时间点（秒，含）
     * @return  CustomResponse对象
     */
    @GetMapping("/danmu-seg/{vid}")
    public CustomResponse getDanmuSegment(@PathVariable("vid") Integer vid,
                                          @RequestParam("start") Double start,
                                          @RequestParam("end") Double end) {
        CustomResponse customResponse = new CustomResponse();
        customResponse.setData(danmuService.getDanmuListByTimeRange(vid, start, end));
        return customResponse;
    }

    /**
     * 删除弹幕
     * @param id    弹幕id
     * @return  响应对象
     */
    @PostMapping("/danmu/delete")
    public CustomResponse deleteDanmu(@RequestParam("id") Integer id) {
        Integer loginUid = currentUser.getUserId();
        return danmuService.deleteDanmu(id, loginUid, currentUser.isAdmin());
    }
}
