package com.example.dream.dal.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.example.dream.common.enums.base.DeleteFlagEnum;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * MyBatis-Plus 自动填充处理器
 * 配合 BasePO 中 @TableField(fill = ...) 注解使用
 */
@Component
public class MyBatisPlusMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        Date now = new Date();
        this.strictInsertFill(metaObject, "createdTime", Date.class, now);
        this.strictInsertFill(metaObject, "modifiedTime", Date.class, now);
        this.strictInsertFill(metaObject, "creator", String.class, "system");
        this.strictInsertFill(metaObject, "editor", String.class, "system");
        this.strictInsertFill(metaObject, "deleteFlag", Integer.class, DeleteFlagEnum.VALID.getCode());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "modifiedTime", Date.class, new Date());
        this.strictUpdateFill(metaObject, "editor", String.class, "system");
    }

}