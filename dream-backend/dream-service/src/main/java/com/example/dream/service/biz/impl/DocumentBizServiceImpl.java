package com.example.dream.service.biz.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.dream.common.enums.base.ResCodeEnum;
import com.example.dream.common.enums.document.FileTypeEnum;
import com.example.dream.common.enums.document.ParserTypeEnum;
import com.example.dream.common.exception.BizException;
import com.example.dream.dal.po.KbDocumentPO;
import com.example.dream.integration.service.minio.OssService;
import com.example.dream.service.biz.DocumentBizService;
import com.example.dream.service.biz.bo.DocumentBO;
import com.example.dream.service.core.KbDocumentCoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link DocumentBizService} 实现，还原 RagFlow local 上传主流程。
 *
 * <p>对应 Python：_upload_local_documents + FileService.upload_document 的核心逻辑。</p>
 *
 * @author dream
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentBizServiceImpl implements DocumentBizService {

    /**
     * 文件名长度限制，对应 RagFlow FILE_NAME_LEN_LIMIT = 255（字节）。
     */
    private static final int FILE_NAME_LEN_LIMIT = 255;

    /**
     * 未开始状态，对应 RagFlow run_status="0"（TaskStatus.UNSTART）。
     */
    private static final String RUN_UNSTART = "0";

    /**
     * 对象存储 key 分隔符。
     */
    private static final String OBJECT_KEY_SEPARATOR = "/";

    private final KbDocumentCoreService kbDocumentCoreService;

    private final OssService ossService;

    @Override
    public List<DocumentBO> uploadLocalDocuments(String datasetId,
                                                 List<MultipartFile> files,
                                                 String userId) {
        // 对应 Python: if "file" not in files -> No file part!
        if (files == null || files.isEmpty()) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "请选择要上传的文件");
        }

        // 对应 Python: 遍历校验每个文件（无文件名 / 文件名过长）
        files.forEach(this::validateFile);

        // 对应 Python: FileService.upload_document —— 逐个文件上传并入库
        List<DocumentBO> result = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            result.add(uploadSingle(datasetId, file, userId));
        }
        return result;
    }

    /**
     * 校验单个文件：文件名非空、长度不超限。
     * 对应 RagFlow _upload_local_documents 中的文件遍历校验。
     *
     * @param file 上传文件
     */
    private void validateFile(MultipartFile file) {
        String filename = file == null ? null : file.getOriginalFilename();
        if (!StringUtils.hasText(filename)) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "存在未选择的文件");
        }
        if (filename.getBytes(StandardCharsets.UTF_8).length > FILE_NAME_LEN_LIMIT) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR,
                    "文件名长度不能超过 " + FILE_NAME_LEN_LIMIT + " 字节");
        }
    }

    /**
     * 单文件上传：文件名去重 -> 类型判断 -> parser 映射 -> 存储 -> 入库。
     * 对应 RagFlow FileService.upload_document 中对单个文件的处理逻辑。
     *
     * @param datasetId 数据集 ID
     * @param file      上传文件
     * @param userId    当前用户 ID
     * @return 文档业务对象
     */
    private DocumentBO uploadSingle(String datasetId, MultipartFile file, String userId) {
        // 对应 Python: duplicate_name —— 同一 kb 下文件名去重
        String filename = duplicateName(datasetId, file.getOriginalFilename());

        // 对应 Python: filename_type —— 依据后缀判断文档类型
        FileTypeEnum fileType = FileTypeEnum.ofFilename(filename);
        if (fileType == FileTypeEnum.OTHER) {
            throw new BizException(ResCodeEnum.DATA_ERROR, "暂不支持该类型的文件");
        }

        // 对应 Python: parser_id 依据类型 / 后缀做映射
        ParserTypeEnum parserType = ParserTypeEnum.resolve(fileType, filename);

        // 对应 Python: STORAGE_IMPL.put —— objectKey 采用 UUID，天然唯一，无需查重
        String objectKey = storeObject(datasetId, filename, file);

        // 对应 Python: doc = {...}; DocumentService.insert(doc)
        KbDocumentPO doc = buildDocument(datasetId, filename, fileType, parserType, objectKey, file.getSize(), userId);
        kbDocumentCoreService.save(doc);

        return toBO(doc);
    }

    /**
     * 将文件写入对象存储，返回最终 objectKey。
     * <p>主流做法：objectKey 使用唯一 ID（UUID）而非原始文件名，从根本上规避命名冲突，
     * 也避免中文/特殊字符导致的 key 不合法问题；原始文件名仅落库用于展示。</p>
     *
     * @param datasetId 数据集 ID
     * @param filename  原始文件名（用于取后缀）
     * @param file      上传文件
     * @return 对象存储 key，形如 {datasetId}/{uuid}.{suffix}
     */
    private String storeObject(String datasetId, String filename, MultipartFile file) {
        String suffix = FileTypeEnum.resolveSuffix(filename);
        String objectName = UUID.randomUUID().toString().replace("-", "");
        if (!suffix.isEmpty()) {
            objectName = objectName + "." + suffix;
        }
        String objectKey = datasetId + OBJECT_KEY_SEPARATOR + objectName;
        try (InputStream in = file.getInputStream()) {
            ossService.putObject(objectKey, in, file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new BizException(ResCodeEnum.SERVER_ERROR, "文件上传失败: " + filename);
        }
        return objectKey;
    }

    /**
     * 构建待入库的文档持久化对象。
     */
    private KbDocumentPO buildDocument(String datasetId, String filename, FileTypeEnum fileType,
                                       ParserTypeEnum parserType, String objectKey, long size, String userId) {
        KbDocumentPO doc = new KbDocumentPO();
        doc.setKbId(datasetId);
        doc.setParserId(parserType.getCode());
        doc.setType(fileType.getCode());
        doc.setFileName(filename);
        doc.setSuffix(FileTypeEnum.resolveSuffix(filename));
        doc.setObjectKey(objectKey);
        doc.setSize(size);
        doc.setRun(RUN_UNSTART);
        doc.setChunkCount(0);
        doc.setTokenCount(0);
        return doc;
    }

    /**
     * 同一 dataset 下文件名去重：若已存在同名文档，追加 (n) 后缀。
     * 对应 RagFlow duplicate_name。
     * <p>优化：一次性查出该 dataset 下已占用的文件名集合，在内存中计算可用名称，
     * 避免原「循环多次 count 查库」的多次数据库往返。</p>
     *
     * @param datasetId 数据集 ID
     * @param name      原始文件名
     * @return 去重后的文件名
     */
    private String duplicateName(String datasetId, String name) {
        // 一次查询取出所有已存在文件名
        Set<String> existingNames = kbDocumentCoreService.list(new LambdaQueryWrapper<KbDocumentPO>()
                        .select(KbDocumentPO::getFileName)
                        .eq(KbDocumentPO::getKbId, datasetId))
                .stream()
                .map(KbDocumentPO::getFileName)
                .collect(Collectors.toSet());

        if (!existingNames.contains(name)) {
            return name;
        }
        String candidate;
        int index = 1;
        do {
            candidate = appendIndex(name, index++);
        } while (existingNames.contains(candidate));
        return candidate;
    }

    /**
     * 在文件名后缀前追加序号，如 a.txt -> a(1).txt。
     */
    private String appendIndex(String name, int index) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            return name.substring(0, dot) + "(" + index + ")" + name.substring(dot);
        }
        return name + "(" + index + ")";
    }

    /**
     * PO 转 BO。
     */
    private DocumentBO toBO(KbDocumentPO doc) {
        DocumentBO bo = new DocumentBO();
        bo.setId(doc.getId() == null ? null : String.valueOf(doc.getId()));
        bo.setName(doc.getFileName());
        bo.setDatasetId(doc.getKbId());
        bo.setChunkMethod(doc.getParserId());
        bo.setChunkCount(doc.getChunkCount());
        bo.setTokenCount(doc.getTokenCount());
        bo.setType(doc.getType());
        bo.setSuffix(doc.getSuffix());
        bo.setSize(doc.getSize());
        bo.setLocation(doc.getObjectKey());
        bo.setRun(doc.getRun());
        return bo;
    }
}