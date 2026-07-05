package com.example.dream.dal.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.dream.dal.po.base.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;


/**
 * 用户表持久化对象
 * * 对应表：{@code @TableName("biz_user")}
 */
@Data
@EqualsAndHashCode(callSuper = true) // 显式声明：包含父类属性
@TableName("biz_user")
public class BizUserPO extends BasePO {


    private String userId;

    private String passwordHash;

    private String role;

    /**
     * 1-启用;0-禁用
     */
    private Integer status;

    private String avatarUrl;

}