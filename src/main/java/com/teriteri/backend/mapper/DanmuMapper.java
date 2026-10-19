package com.teriteri.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teriteri.backend.pojo.Danmu;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DanmuMapper extends BaseMapper<Danmu> {

    /**
     * 批量插入弹幕（MQ 消费端一批一次写入，替代逐条 insert），插入后回填自增主键 id
     * @param list  弹幕列表
     * @return  插入条数
     */
    @Insert("<script>" +
            "insert into danmu (vid, uid, content, fontsize, mode, color, time_point, state, create_date) values " +
            "<foreach collection='list' item='d' separator=','>" +
            "(#{d.vid}, #{d.uid}, #{d.content}, #{d.fontsize}, #{d.mode}, #{d.color}, #{d.timePoint}, #{d.state}, #{d.createDate})" +
            "</foreach>" +
            "</script>")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertBatch(@Param("list") List<Danmu> list);
}
