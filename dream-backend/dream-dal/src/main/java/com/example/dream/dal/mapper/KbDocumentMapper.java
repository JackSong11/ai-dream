package com.example.dream.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.dream.dal.po.KbDocumentPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档表 Mapper 接口
 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocumentPO> {

}