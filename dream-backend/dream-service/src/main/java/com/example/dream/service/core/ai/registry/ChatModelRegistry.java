package com.example.dream.service.core.ai.registry;

import com.example.dream.common.exception.BizException;
import com.example.dream.service.core.ai.config.DreamAiProperties;
import com.example.dream.service.core.ai.config.ModelProperties;
import com.example.dream.service.core.ai.config.ProviderProperties;
import com.example.dream.service.core.ai.factory.ChatModelFactory;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册表与路由中心。
 * <p>
 * 启动时依据配置，通过工厂构建每个模型对应的 {@link ChatClient} 并缓存，
 * 业务侧通过 modelKey 获取对应 ChatClient，实现运行时按切换。
 * 支持 { #refresh()} 热更新。
 *
 * @author dream
 */
@Slf4j
@Component
public class ChatModelRegistry {

    private final DreamAiProperties properties;

    private final ChatModelFactory factory;

    /**
     * modelKey -> ChatClient 缓存。
     */
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();

    /**
     * modelKey -> ChatModel 缓存（供手动工具调用循环使用）。
     */
    private final Map<String, ChatModel> modelCache = new ConcurrentHashMap<>();

    /**
     * modelKey -> 模型配置（用于列表展示）。
     */
    private final Map<String, ModelProperties> modelMetaCache = new ConcurrentHashMap<>();

    /**
     * 默认模型 key。
     * -- GETTER --
     *  获取默认模型 key。

     */
    @Getter
    private volatile String defaultModelKey;

    public ChatModelRegistry(DreamAiProperties properties, ChatModelFactory factory) {
        this.properties = properties;
        this.factory = factory;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * 重新加载所有模型（配置变更后可调用）。
     */
    public synchronized void refresh() {
        clientCache.clear();
        modelMetaCache.clear();
        List<ProviderProperties> providers = properties.getProviders();
        if (CollectionUtils.isEmpty(providers)) {
            log.warn("[ChatModelRegistry] 未配置任何供应商 (dream.ai.providers 为空)");
            return;
        }

        String primaryKey = null;
        String firstKey = null;
        // 两层遍历：供应商 -> 其下模型。同一供应商共享 baseUrl / apiKey
        for (ProviderProperties provider : providers) {
            if (CollectionUtils.isEmpty(provider.getModels())) {
                continue;
            }
            for (ModelProperties model : provider.getModels()) {
                if (!StringUtils.hasText(model.getKey())) {
                    log.warn("[ChatModelRegistry] 跳过一个缺少 key 的模型配置: {}", model);
                    continue;
                }
                if (clientCache.containsKey(model.getKey())) {
                    throw new BizException("模型 key 全局重复: " + model.getKey());
                }
                // 工厂按供应商 type 路由并注入连接参数，再包装为 ChatClient 缓存
                ChatModel chatModel = factory.create(provider, model);
                ChatClient client = ChatClient.builder(chatModel).build();
                clientCache.put(model.getKey(), client);
                modelCache.put(model.getKey(), chatModel);
                modelMetaCache.put(model.getKey(), model);
                if (!StringUtils.hasText(firstKey)) {
                    firstKey = model.getKey();
                }
                if (model.isPrimary()) {
                    primaryKey = model.getKey();
                }
                log.info("[ChatModelRegistry] 已注册模型: providerId={}, key={}, name={}, model={}",
                        provider.getId(), model.getKey(), model.getName(), model.getModel());
            }
        }

        if (!StringUtils.hasText(firstKey)) {
            log.warn("[ChatModelRegistry] 所有供应商下均无有效模型");
            return;
        }

        // 默认模型优先级：primary 标记 > 配置 defaultModel > 第一个
        this.defaultModelKey = StringUtils.hasText(primaryKey) ? primaryKey
                : StringUtils.hasText(properties.getDefaultModel()) ? properties.getDefaultModel()
                : firstKey;
        log.info("[ChatModelRegistry] 默认模型: {}", defaultModelKey);
    }

    /**
     * 按 modelKey 获取 ChatClient；modelKey 为空时使用默认模型。
     */
    public ChatClient getClient(String modelKey) {
        String key = StringUtils.hasText(modelKey) ? modelKey : defaultModelKey;
        ChatClient client = clientCache.get(key);
        if (client == null) {
            throw new BizException("模型不存在或未配置: " + key + "，可用模型: " + clientCache.keySet());
        }
        return client;
    }

    /**
     * 列出所有可用模型的元信息（key + name + model）。
     */
    public List<ModelProperties> listModels() {
        return new ArrayList<>(modelMetaCache.values());
    }

    /**
     * 判断某个 modelKey 是否可用。
     */
    public boolean exists(String modelKey) {
        return clientCache.containsKey(modelKey);
    }

    /**
     * 按 modelKey 获取底层 ChatModel（供手动工具调用循环使用）；为空用默认模型。
     */
    public ChatModel getChatModel(String modelKey) {
        String key = StringUtils.hasText(modelKey) ? modelKey : defaultModelKey;
        ChatModel model = modelCache.get(key);
        if (model == null) {
            throw new BizException("模型不存在或未配置: " + key + "，可用模型: " + modelCache.keySet());
        }
        return model;
    }
}