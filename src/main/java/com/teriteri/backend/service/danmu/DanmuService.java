package com.teriteri.backend.service.danmu;

import com.teriteri.backend.pojo.CustomResponse;
import com.teriteri.backend.pojo.Danmu;

import java.util.List;
import java.util.Set;

public interface DanmuService {

    /** 弹幕分段缓存key前缀：danmu_seg:{vid}，zset 以时间点(time_point)为分数、弹幕ID为成员 */
    String SEG_CACHE_KEY_PREFIX = "danmu_seg:";

    /**
     * 根据弹幕ID集合查询弹幕列表
     * @param idset 弹幕ID集合
     * @return  弹幕列表
     */
    List<Danmu> getDanmuListByIdset(Set<Object> idset);

    /**
     * 按视频时间点范围查询弹幕（分段缓存读取，缓存未命中时从库重建分段缓存）
     * @param vid   视频ID
     * @param start 起始时间点（秒，含）
     * @param end   结束时间点（秒，含）
     * @return  该时间范围内的弹幕列表
     */
    List<Danmu> getDanmuListByTimeRange(Integer vid, double start, double end);

    /**
     * 删除弹幕
     * @param id    弹幕id
     * @param uid   操作用户
     * @param isAdmin   是否管理员
     * @return  响应对象
     */
    CustomResponse deleteDanmu(Integer id, Integer uid, boolean isAdmin);
}
