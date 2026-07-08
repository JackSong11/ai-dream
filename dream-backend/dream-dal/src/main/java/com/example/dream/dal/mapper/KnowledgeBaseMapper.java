package com.example.dream.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.dream.dal.po.KnowledgeBasePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库表 Mapper 接口
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBasePO> {

}