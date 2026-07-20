package com.example.dream.service.core.ai.provider;

import com.example.dream.service.core.ai.config.ModelProperties;
import com.example.dream.service.core.ai.config.ProviderProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * OpenAI 兼容协议提供者。
 * <p>
 * 覆盖绝大多数国产/开源模型：通义千问、DeepSeek、Kimi、智谱、
 * 京东云 AI 网关等，它们均兼容 OpenAI 的 REST 协议。
 * <p>
 * 连接参数（baseUrl / apiKey）默认取供应商级，模型可选择性覆盖；
 * 因此同一网关下切换模型只需变更 model 名。
 *
 * @author dream
 */
@Component
public class OpenAiCompatibleChatClientProvider implements ChatClientProvider {

    /**
     * 该实现支持的协议类型标识。
     */
    public static final String TYPE = "openai-compatible";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public ChatClient create(ProviderProperties provider, ModelProperties model) {
        // 连接参数级联：模型级覆盖 > 供应商级
        String baseUrl = StringUtils.hasText(model.getBaseUrl())
                ? model.getBaseUrl() : provider.getBaseUrl();
        String apiKey = StringUtils.hasText(model.getApiKey())
                ? model.getApiKey() : provider.getApiKey();
        // 温度级联：模型级 > 供应商级
        Double temperature = model.getTemperature() != null
                ? model.getTemperature() : provider.getTemperature();

        // Spring AI 2.0 中 baseUrl / apiKey / model 等统一配置在 OpenAiChatOptions 上
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model.getModel())
                .temperature(temperature);
        if (model.getMaxTokens() != null) {
            optionsBuilder.maxTokens(model.getMaxTokens());
        }

        return ChatClient.builder(OpenAiChatModel.builder()
                .options(optionsBuilder.build())
                .build()).build();
    }
}
