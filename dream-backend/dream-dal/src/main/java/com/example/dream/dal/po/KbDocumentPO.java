package com.example.dream.dal.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.dream.dal.po.base.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 文档表持久化对象
 * kb_document
 */
@Data
@EqualsAndHashCode(callSuper = true) // 显式声明：包含父类属性
@TableName("kb_document")
public class KbDocumentPO extends BasePO {

    /**
     * 文档名称
     */
    private String fileName;

    private String objectKey;

    private String status;

    /**
     * 分块数量
     */
    private Integer chunkCount;

    /**
     * 错误信息
     */
    private String errorMsg;
}