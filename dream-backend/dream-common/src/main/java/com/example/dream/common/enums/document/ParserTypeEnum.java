package com.example.dream.common.enums.document;

import lombok.Getter;

/**
 * 解析器类型枚举，对应 RagFlow ParserType。
 *
 * @author dream
 */
@Getter
public enum ParserTypeEnum {

    /**
     * 默认解析器
     */
    NAIVE("naive"),

    /**
     * 图片解析器
     */
    PICTURE("picture"),

    /**
     * 音频解析器
     */
    AUDIO("audio"),

    /**
     * 演示文稿解析器（ppt/pptx/pages）
     */
    PRESENTATION("presentation"),

    /**
     * 邮件解析器（eml）
     */
    EMAIL("email");

    /**
     * 解析器编码
     */
    private final String code;

    ParserTypeEnum(String code) {
        this.code = code;
    }

    /**
     * 依据文档类型与文件名推断解析器，对应 RagFlow upload_document 中 parser_id 的映射规则。
     * <p>优先级：presentation / email 后缀 > 文档类型（图片、音频） > 默认 naive。</p>
     *
     * @param fileType 文档类型
     * @param filename 文件名
     * @return 匹配的解析器类型
     */
    public static ParserTypeEnum resolve(FileTypeEnum fileType, String filename) {
        String suffix = FileTypeEnum.resolveSuffix(filename);
        if (isPresentation(suffix)) {
            return PRESENTATION;
        }
        if ("eml".equals(suffix)) {
            return EMAIL;
        }
        return switch (fileType) {
            case VISUAL -> PICTURE;
            case AURAL -> AUDIO;
            default -> NAIVE;
        };
    }

    private static boolean isPresentation(String suffix) {
        return "ppt".equals(suffix) || "pptx".equals(suffix) || "pages".equals(suffix);
    }
}