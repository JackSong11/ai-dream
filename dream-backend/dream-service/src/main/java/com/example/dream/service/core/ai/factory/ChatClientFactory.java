package com.example.dream.service.core.ai.factory;

import com.example.dream.common.exception.BizException;
import com.example.dream.service.core.ai.config.ModelProperties;
import com.example.dream.service.core.ai.config.ProviderProperties;
import com.example.dream.service.core.ai.provider.ChatClientProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 多模型 ChatClient 工厂。
 * <p>
 * 启动时由 Spring 注入所有 {ChatClientProvider} 实现，
 * 按 type 建立索引。构建模型时根据供应商的 type 路由到对应 provider。
 * 新增协议只需新增 provider 实现类，工厂无需任何改动。
 *
 * @author dream
 */
@Component
public class ChatClientFactory {

    /**
     * 协议类型 -> 提供者 的映射表。
     */
    private final Map<String, ChatClientProvider> providerMap;

    // 构造器注入，Spring容器在创建ChatClientFactory Bean的时候Spring 会分析：参数类型是 List<ChatClientProvider>。
    // 于是它会去 IOC 容器中找：所有实现了 ChatClientProvider 的 Bean，然后自动组成一个 List，这个 List 就作为参数传给构造器。
    // 注意，就算你有多个兼容openAi的baseurl，在配置文件里面写多个provider配置，也只能代表ProviderProperties有多个。
    // 在Spring的Bean容器里面，ChatClientProvider也会有一个，因为你的ChatClientProvider类代码只有一个，和配置文件的provider配置无关
    public ChatClientFactory(List<ChatClientProvider> providers) {
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(ChatClientProvider::type, Function.identity()));
    }

    /**
     * 根据供应商与模型配置构建 ChatClient。
     *
     * @param provider 供应商配置（决定协议类型与连接参数）
     * @param model    模型配置
     * @return 构建好的 ChatClient
     * @throws BizException 当供应商的 type 没有对应的 provider 时
     */
    public ChatClient create(ProviderProperties provider, ModelProperties model) {
        ChatClientProvider impl = providerMap.get(provider.getType());
        if (impl == null) {
            throw new BizException("不支持的模型协议类型: " + provider.getType()
                    + "，可用类型: " + providerMap.keySet());
        }
        return impl.create(provider, model);
    }

    /**
     * 当前已注册的协议类型集合。
     */
    public java.util.Set<String> supportedTypes() {
        return providerMap.keySet();
    }
}
