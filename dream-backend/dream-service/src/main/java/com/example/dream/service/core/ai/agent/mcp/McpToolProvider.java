package com.example.dream.service.core.ai.agent.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 工具提供者，对应 nanobot agent.tools.mcp 的能力（但不移植其自研 JSON-RPC 协议栈）。
 * <p>
 * <b>用 Spring AI 2.0 内建 MCP Client 承载，去掉消息总线：</b>
 * <ul>
 *   <li><b>工具调用</b>：MCP server 的工具由 Spring AI 的
 *       {@link SyncMcpToolCallbackProvider} 自动暴露为 {@link ToolCallback}
 *       （底层 {@code McpSyncClient} 由 {@code McpClientAutoConfiguration} 依配置创建），
 *       调用时在 AgentRunner 的工具循环里同步执行，完全不碰队列；</li>
 *   <li><b>运行时热重载</b>：nanobot 通过 system 控制消息经队列通知常驻 loop 重连，
 *       这里直接调用 {@link #reload()} 方法即可（同步架构下无需给自己发消息），
 *       内部调用 provider 的 {@code invalidateCache()} 使工具列表在下次获取时刷新。</li>
 * </ul>
 * <p>
 * MCP 未配置时（无 {@link SyncMcpToolCallbackProvider} bean）本类优雅降级，
 * {@link #getToolCallbacks()} 返回空列表，不影响主 Agent 运行。
 *
 * @author dream
 */
@Slf4j
@Component
public class McpToolProvider {

    /**
     * Spring AI 自动装配的 MCP 工具回调提供者，可能不存在（未配置 MCP server）。
     */
    private final ObjectProvider<SyncMcpToolCallbackProvider> providerObjectProvider;

    public McpToolProvider(ObjectProvider<SyncMcpToolCallbackProvider> providerObjectProvider) {
        this.providerObjectProvider = providerObjectProvider;
    }

    /**
     * 获取当前所有 MCP 工具的 ToolCallback（供 AgentRunner 合并进工具列表）。
     * <p>
     * 未配置 MCP 时返回空列表。
     *
     * @return MCP 工具回调列表
     */
    public List<ToolCallback> getToolCallbacks() {
        SyncMcpToolCallbackProvider provider = providerObjectProvider.getIfAvailable();
        if (provider == null) {
            return List.of();
        }
        try {
            ToolCallback[] callbacks = provider.getToolCallbacks();
            return callbacks == null ? List.of() : new ArrayList<>(List.of(callbacks));
        } catch (Exception e) {
            log.warn("[McpToolProvider] 获取 MCP 工具失败，本回合跳过 MCP 工具: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 运行时热重载 MCP 工具（对应 nanobot 的 request_mcp_reload，但改为直接方法调用）。
     * <p>
     * 使 provider 缓存失效，下次 {@link #getToolCallbacks()} 会重新拉取 MCP server 的工具。
     * WebUI 修改 MCP 配置后可调用本方法生效。
     */
    public void reload() {
        SyncMcpToolCallbackProvider provider = providerObjectProvider.getIfAvailable();
        if (provider == null) {
            log.info("[McpToolProvider] 未配置 MCP server，reload 无需执行");
            return;
        }
        provider.invalidateCache();
        log.info("[McpToolProvider] 已触发 MCP 工具热重载（缓存失效，下次获取将刷新）");
    }

    /**
     * MCP 是否可用（已配置且装配成功）。
     */
    public boolean isAvailable() {
        return providerObjectProvider.getIfAvailable() != null;
    }
}