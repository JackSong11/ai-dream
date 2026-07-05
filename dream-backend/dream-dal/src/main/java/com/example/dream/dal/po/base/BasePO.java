package com.example.dream.dal.po.base;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * @author songzhiquan1
 */
@Data
public class BasePO implements Serializable {

    /**
     * id
     */
//    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 创建人
     */
    @TableField(value = "creator", fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
    private String creator;

    /**
     * 修改人
     */
    @TableField(value = "editor", fill = FieldFill.INSERT_UPDATE)
    private String editor;

    /**
     * 创建时间
     */
    @TableField(value = "created_time", fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
    private Date createdTime;

    /**
     * 修改时间
     */
    @TableField(value = "modified_time", fill = FieldFill.INSERT_UPDATE)
    private Date modifiedTime;

    /**
     * 逻辑删除字段（0-有效，1-逻辑删除）
     */
    @TableLogic
    @TableField(value = "delete_flag", fill = FieldFill.INSERT)
    private Integer deleteFlag;
}
