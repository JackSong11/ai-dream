package com.example.dream.service.core.ai.provider;

import com.example.dream.service.core.ai.config.ModelProperties;
import com.example.dream.service.core.ai.config.ProviderProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * DeepSeek 原生协议提供者。
 * <p>
 * 使用 Spring AI 的 {DeepSeekChatModel} 接入 DeepSeek 官方 API，
 * 连接参数和温度均支持“模型级覆盖供应商级”的配置方式。
 *
 * @author dream
 */
@Component
public class DeepSeekChatClientProvider implements ChatClientProvider {

    /**
     * 该实现支持的协议类型标识。
     */
    public static final String TYPE = "deepseek";

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public ChatClient create(ProviderProperties provider, ModelProperties model) {
        String baseUrl = StringUtils.hasText(model.getBaseUrl())
                ? model.getBaseUrl()
                : StringUtils.hasText(provider.getBaseUrl()) ? provider.getBaseUrl() : DEFAULT_BASE_URL;
        String apiKey = StringUtils.hasText(model.getApiKey())
                ? model.getApiKey() : provider.getApiKey();
        Double temperature = model.getTemperature() != null
                ? model.getTemperature() : provider.getTemperature();

        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        DeepSeekChatOptions.Builder optionsBuilder = DeepSeekChatOptions.builder()
                .model(model.getModel())
                .temperature(temperature);
        if (model.getMaxTokens() != null) {
            optionsBuilder.maxTokens(model.getMaxTokens());
        }

        return ChatClient.builder(DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .options(optionsBuilder.build())
                .build()).build();
    }
}
