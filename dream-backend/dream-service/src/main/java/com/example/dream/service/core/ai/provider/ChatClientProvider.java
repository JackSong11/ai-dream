package com.example.dream.service.core.ai.provider;

import com.example.dream.service.core.ai.config.ModelProperties;
import com.example.dream.service.core.ai.config.ProviderProperties;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 模型协议提供者（策略接口）。
 * <p>
 * 每种协议族（如 openai-compatible、ollama、anthropic 原生）对应一个实现类。
 * 新增一种全新协议时，只需新增一个实现类并交给 Spring 管理，
 * 工厂会自动收集，无需修改任何已有代码——符合开闭原则。
 *
 * @author dream
 */
public interface ChatClientProvider {

    /**
     * 声明本实现支持的协议类型。
     * 与 {ProviderProperties#getType()} 对应。
     *
     * @return 协议类型标识，如 openai-compatible
     */
    String type();

    /**
     * 依据供应商与模型配置构建对应的 ChatClient 实例。
     * <p>
     * 连接参数（baseUrl / apiKey）来自 provider，模型名来自 model，
     * 从而实现同一供应商下多模型共享连接配置。
     *
     * @param provider 供应商（接入点）配置
     * @param model    模型配置
     * @return 可供业务调用的 ChatClient
     */
    ChatClient create(ProviderProperties provider, ModelProperties model);
}
