package com.example.dream.service.biz;

import com.example.dream.service.core.ai.config.ModelProperties;
import com.example.dream.service.core.ai.registry.ChatModelRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 多模型对话业务编排服务。
 * <p>
 * 对上层屏蔽模型选择细节：Web 层只需传入 modelKey + 提示词，
 * 由本服务通过 { ChatModelRegistry} 路由到对应模型执行对话。
 *
 * @author dream
 */
@Service
@RequiredArgsConstructor
public class MultiModelService {

    private final ChatModelRegistry registry;

    /**
     * 列出所有可用模型。
     */
    public List<ModelProperties> listModels() {
        return registry.listModels();
    }

    /**
     * 当前默认模型 key。
     */
    public String defaultModelKey() {
        return registry.getDefaultModelKey();
    }

    /**
     * 校验模型是否存在。
     */
    public boolean modelExists(String modelKey) {
        return registry.exists(modelKey);
    }

    /**
     * 同步（阻塞）对话。
     *
     * @param modelKey     模型标识，为空时使用默认模型
     * @param systemPrompt 系统提示词（可为空）
     * @param userInput    用户输入
     * @return 模型完整回复文本
     */
    public String chat(String modelKey, String systemPrompt, String userInput) {
        var prompt = registry.getClient(modelKey).prompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt = prompt.system(systemPrompt);
        }
        return prompt.user(userInput).call().content();
    }

    /**
     * 流式（SSE）对话。
     *
     * @param modelKey     模型标识，为空时使用默认模型
     * @param systemPrompt 系统提示词（可为空）
     * @param userInput    用户输入
     * @return 流式响应
     */
    public Flux<ChatResponse> streamChat(String modelKey, String systemPrompt, String userInput) {
        var prompt = registry.getClient(modelKey).prompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt = prompt.system(systemPrompt);
        }
        return prompt.user(userInput).stream().chatResponse();
    }

    /**
     * 带工具的简单 Agent 对话（同步）。
     * <p>
     * 演示 Agent 能力：将工具对象交给模型，由模型自主决定是否调用。
     *
     * @param modelKey  模型标识
     * @param userInput 用户输入
     * @param tools     工具对象数组
     * @return 模型回复
     */
    public String agentChat(String modelKey, String userInput, Object... tools) {
        return registry.getClient(modelKey).prompt()
                .user(userInput)
                .tools(tools)
                .call()
                .content();
    }
}