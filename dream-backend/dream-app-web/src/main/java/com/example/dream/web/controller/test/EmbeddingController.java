package com.example.dream.web.controller.test;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Spring AI 2.0 嵌入向量生成示例。
 * 嵌入模型：Qwen3-Embedding-8B（通过 OpenAI 兼容协议接入）。
 */
@RestController
public class EmbeddingController {

    /**
     * Spring AI 自动装配的嵌入模型。
     * 底层由 spring-ai-starter-model-openai 提供，
     * 使用 application.yml 中 spring.ai.openai.embedding 的配置。
     */
    @Autowired
    private EmbeddingModel embeddingModel;

    /**
     * 示例 1：最简单的单文本嵌入。
     * 直接使用默认模型（Qwen3-Embedding-8B）生成向量。
     */
    @GetMapping("/ai/embedding")
    public Map<String, Object> embed(
            @RequestParam(value = "text", defaultValue = "你好，Spring AI") String text) {
        float[] vector = embeddingModel.embed(text);
        return Map.of(
                "text", text,
                "dimensions", vector.length,
                "embedding", vector);
    }

    /**
     * 示例 2：批量文本嵌入。
     * 一次请求为多个文本生成向量。
     */
    @PostMapping("/ai/embedding/batch")
    public Map<String, Object> embedBatch(@RequestBody List<String> texts) {
        List<float[]> vectors = embeddingModel.embed(texts);
        return Map.of(
                "count", vectors.size(),
                "dimensions", vectors.isEmpty() ? 0 : vectors.get(0).length,
                "embeddings", vectors);
    }

    /**
     * 示例 3：使用 EmbeddingRequest 显式指定模型与选项。
     * 可在运行时覆盖 application.yml 中的默认模型配置。
     */
    @PostMapping("/ai/embedding/request")
    public EmbeddingResponse embedWithOptions(@RequestBody List<String> texts) {
        EmbeddingRequest request = new EmbeddingRequest(
                texts,
                OpenAiEmbeddingOptions.builder()
                        .model("Qwen3-Embedding-8B")
                        .build());
        return embeddingModel.call(request);
    }

    /**
     * 示例 4：对 Document 生成嵌入向量（RAG 场景常用）。
     */
    @GetMapping("/ai/embedding/document")
    public Map<String, Object> embedDocument(
            @RequestParam(value = "content", defaultValue = "这是一段用于向量化的文档内容") String content) {
        Document document = new Document(content);
        float[] vector = embeddingModel.embed(document);
        return Map.of(
                "documentId", document.getId(),
                "dimensions", vector.length,
                "embedding", vector);
    }
}