package com.example.dream.common.vo;

import com.example.dream.common.enums.base.ResCodeEnum;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结果包装类，用于 Controller 层向前端返回数据
 *
 * @author songzhiquan1
 * @date 2026-07-05
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 响应码
     */
    private String code;

    /**
     * 响应消息
     */
    private String msg;

    /**
     * 响应数据
     */
    private T data;

    private Result() {
    }

    private Result(String code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    // ==================== 成功 ====================

    /**
     * 成功（无数据）
     */
    public static <T> Result<T> success() {
        return new Result<>(ResCodeEnum.SUCCESS.getCode(), ResCodeEnum.SUCCESS.getDesc(), null);
    }

    /**
     * 成功（带数据）
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ResCodeEnum.SUCCESS.getCode(), ResCodeEnum.SUCCESS.getDesc(), data);
    }

    /**
     * 成功（自定义消息 + 数据）
     */
    public static <T> Result<T> success(String msg, T data) {
        return new Result<>(ResCodeEnum.SUCCESS.getCode(), msg, data);
    }

    // ==================== 失败 ====================

    /**
     * 失败（默认消息）
     */
    public static <T> Result<T> fail() {
        return new Result<>(ResCodeEnum.FAIL.getCode(), ResCodeEnum.FAIL.getDesc(), null);
    }

    /**
     * 失败（自定义消息）
     */
    public static <T> Result<T> fail(String msg) {
        return new Result<>(ResCodeEnum.FAIL.getCode(), msg, null);
    }

    // ==================== 指定枚举 ====================

    /**
     * 根据响应码枚举返回结果
     */
    public static <T> Result<T> of(ResCodeEnum resCodeEnum) {
        return new Result<>(resCodeEnum.getCode(), resCodeEnum.getDesc(), null);
    }

    /**
     * 根据响应码枚举返回结果（带数据）
     */
    public static <T> Result<T> of(ResCodeEnum resCodeEnum, T data) {
        return new Result<>(resCodeEnum.getCode(), resCodeEnum.getDesc(), data);
    }

    /**
     * 根据响应码枚举返回结果（自定义消息 + 数据）
     */
    public static <T> Result<T> of(ResCodeEnum resCodeEnum, String msg, T data) {
        return new Result<>(resCodeEnum.getCode(), msg, data);
    }

    // ==================== 自定义 ====================

    /**
     * 自定义响应码和消息
     */
    public static <T> Result<T> build(String code, String msg) {
        return new Result<>(code, msg, null);
    }

    /**
     * 自定义响应码、消息和数据
     */
    public static <T> Result<T> build(String code, String msg, T data) {
        return new Result<>(code, msg, data);
    }

    // ==================== 判断方法 ====================

    /**
     * 是否成功
     */
    public boolean isSuccess() {
        return ResCodeEnum.SUCCESS.getCode().equals(this.code);
    }
}