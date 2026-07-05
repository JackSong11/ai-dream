package com.example.dream.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.dream.dal.po.BizUserPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper 接口
 */
@Mapper
public interface BizUserMapper extends BaseMapper<BizUserPO> {

}