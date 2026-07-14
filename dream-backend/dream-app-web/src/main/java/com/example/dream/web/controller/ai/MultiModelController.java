package com.example.dream.web.controller.ai;

import com.example.dream.common.vo.Result;
import com.example.dream.service.biz.MultiModelService;
import com.example.dream.web.controller.ai.tool.DateTimeTool;
import com.example.dream.web.vo.ai.ChatReqVO;
import com.example.dream.web.vo.ai.ModelInfoVO;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 多模型接口。
 * <p>
 * 提供：模型列表查询、同步对话、流式对话、Agent 工具对话。
 *
 * @author dream
 */
@RestController
@RequestMapping("/ai/multi-model")
@RequiredArgsConstructor
public class MultiModelController {

    private final MultiModelService multiModelService;

    /**
     * 1. 查询所有可用模型列表（供前端下拉展示）。
     */
    @GetMapping("/models")
    public Result<List<ModelInfoVO>> listModels() {
        String defaultKey = multiModelService.defaultModelKey();
        List<ModelInfoVO> list = multiModelService.listModels().stream().map(m -> {
            ModelInfoVO vo = new ModelInfoVO();
            vo.setModelKey(m.getKey());
            vo.setName(m.getName());
            vo.setModel(m.getModel());
            vo.setCurrent(m.getKey().equals(defaultKey));
            return vo;
        }).toList();
        return Result.success(list);
    }

    /**
     * 2. 同步对话。
     * <p>
     * modelKey 优先级：请求体 > 默认模型。
     */
    @PostMapping("/chat")
    public Result<String> chat(@RequestBody ChatReqVO req) {
        String modelKey = resolveModelKey(req.getModelKey());
        String answer = multiModelService.chat(modelKey, req.getSystemPrompt(), req.getMessage());
        return Result.success(answer);
    }

    /**
     * 3. 流式对话（SSE）。
     * <p>
     * 使用 GET + query 参数，便于浏览器 EventSource 直接连接。
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatResponse> streamChat(
            @RequestParam(required = false) String modelKey,
            @RequestParam(required = false) String systemPrompt,
            @RequestParam String message) {
        String resolvedKey = resolveModelKey(modelKey);
        return multiModelService.streamChat(resolvedKey, systemPrompt, message);
    }

    /**
     * 4. 简单 Agent 对话（带工具调用）。
     * <p>
     * 演示：模型可自主决定调用 DateTimeTool 获取时间 / 设置闹钟。
     * 例如输入「帮我在 10 分钟后设置一个闹钟」。
     */
    @PostMapping("/agent")
    public Result<String> agentChat(@RequestBody ChatReqVO req) {
        String modelKey = resolveModelKey(req.getModelKey());
        String answer = multiModelService.agentChat(modelKey, req.getMessage(), new DateTimeTool());
        return Result.success(answer);
    }

    /**
     * 解析最终使用的 modelKey。
     * 优先级：显式传入 > 默认模型。
     */
    private String resolveModelKey(String explicitKey) {
        if (StringUtils.hasText(explicitKey)) {
            return explicitKey;
        }
        return multiModelService.defaultModelKey();
    }
}