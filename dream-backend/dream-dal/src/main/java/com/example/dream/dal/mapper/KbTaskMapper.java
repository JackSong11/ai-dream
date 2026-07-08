package com.example.dream.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.dream.dal.po.KbTaskPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档解析任务表 Mapper 接口
 */
@Mapper
public interface KbTaskMapper extends BaseMapper<KbTaskPO> {

}