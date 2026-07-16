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

}