package com.example.dream.common.enums.document;

import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * 文档类型枚举，对应 RagFlow FileType。
 * <p>内置各类型对应的文件后缀集合，用于依据文件名识别类型。</p>
 *
 * @author dream
 */
@Getter
public enum FileTypeEnum {

    /**
     * 图片类
     */
    VISUAL("visual", Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp", "tif", "tiff")),

    /**
     * 音频类
     */
    AURAL("aural", Set.of("mp3", "wav", "m4a", "flac", "aac", "ogg")),

    /**
     * 通用文档类
     */
    DOC("doc", Set.of("pdf", "txt", "md", "markdown", "docx", "doc", "xlsx", "xls",
            "csv", "pptx", "ppt", "pages", "eml", "html", "htm", "json")),

    /**
     * 不支持的类型
     */
    OTHER("other", Set.of());

    /**
     * 类型编码
     */
    private final String code;

    /**
     * 该类型包含的文件后缀集合（小写，不含 "."）
     */
    private final Set<String> suffixes;

    FileTypeEnum(String code, Set<String> suffixes) {
        this.code = code;
        this.suffixes = suffixes;
    }

    /**
     * 依据文件名后缀识别文档类型，对应 RagFlow filename_type。
     *
     * @param filename 文件名
     * @return 匹配到的类型，未匹配返回 {@link #OTHER}
     */
    public static FileTypeEnum ofFilename(String filename) {
        String suffix = resolveSuffix(filename);
        if (suffix.isEmpty()) {
            return OTHER;
        }
        return Arrays.stream(values())
                .filter(type -> type.suffixes.contains(suffix))
                .findFirst()
                .orElse(OTHER);
    }

    /**
     * 取文件后缀（小写，不含 "."），对应 Python Path(filename).suffix.lstrip(".")。
     *
     * @param filename 文件名
     * @return 后缀，无后缀返回空串
     */
    public static String resolveSuffix(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}