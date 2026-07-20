package com.example.dream.service.core.ai.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 供应商（接入点）配置。
 * <p>
 * 一个供应商代表一个「协议 + URL + 密钥」的接入点，
 * 其下可挂载多个共享同一连接参数的模型。
 * 适用于同一网关（如京东云 AI 网关）下切换不同模型名的场景：
 * URL / apiKey 只需配置一次。
 *
 * @author dream
 */
@Data
public class ProviderProperties {

    /**
     * 供应商唯一标识，如：jdcloud、moonshot。
     */
    private String id;

    /**
     * 协议类型，决定由哪个 Provider 构建对应 ChatClient。
     * 例如：openai-compatible、deepseek。
     */
    private String type;

    /**
     * 接入点服务地址（该供应商下所有模型共享）。
     */
    private String baseUrl;

    /**
     * 访问密钥（该供应商下所有模型共享）。支持 ${ENV_VAR} 占位注入。
     */
    private String apiKey;

    /**
     * 供应商级默认温度。模型未单独指定时使用。
     */
    private Double temperature = 0.7;

    /**
     * 该供应商下的模型列表。
     */
    private List<ModelProperties> models = new ArrayList<>();
}
