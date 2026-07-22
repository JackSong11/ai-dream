package com.example.dream.web.controller.ai;

import com.example.dream.common.context.UserContext;
import com.example.dream.common.exception.BizException;
import com.example.dream.common.vo.Result;
import com.example.dream.service.agent.AgentLoop;
import com.example.dream.service.agent.record.AgentRunRequest;
import com.example.dream.service.agent.record.AgentRunResult;
import com.example.dream.web.vo.ai.AgentCompletionReqVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentLoop agentLoop;

    @PostMapping("/completions")
    public Result<Map<String, Object>> complete(@RequestBody AgentCompletionReqVO body) {
        AgentRunRequest request = toRequest(body, UserContext.getUserId());
        AgentRunResult result = agentLoop.run(request, null);
        return Result.success(answer(result, body));
    }

    @PostMapping(value = "/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody AgentCompletionReqVO body) {
        String userId = UserContext.getUserId();
        AgentRunRequest request = toRequest(body, userId);
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            AtomicReference<String> accumulated = new AtomicReference<>("");
            try {
                agentLoop.run(request, delta -> {
                    String full = accumulated.updateAndGet(previous -> previous + delta);
                    send(emitter, frame(full, false, body));
                });
                send(emitter, frame("", true, body));
                emitter.complete();
            } catch (Exception e) {
                send(emitter, Map.of("code", 500, "message", safeMessage(e)));
                emitter.complete();
            }
        });
        return emitter;
    }

    private AgentRunRequest toRequest(AgentCompletionReqVO body, String userId) {
        if (body == null || body.getDialogId() == null || body.getConvId() == null
                || !StringUtils.hasText(body.latestUserText())) {
            throw new BizException("dialogId、convId 和用户消息不能为空");
        }
        return new AgentRunRequest(body.getDialogId(), body.getConvId(), userId,
                body.getModelKey(), body.latestUserText().trim());
    }

    private Map<String, Object> answer(AgentRunResult result, AgentCompletionReqVO body) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", result.answer());
        data.put("reference", Map.of("toolsUsed", result.toolsUsed(), "stopReason", result.stopReason()));
        data.put("id", body.getConvId());
        data.put("convId", body.getConvId());
        data.put("dialogId", body.getDialogId());
        return data;
    }

    private Map<String, Object> frame(String answer, boolean isFinal, AgentCompletionReqVO body) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", answer);
        data.put("final", isFinal);
        data.put("convId", body.getConvId());
        data.put("dialogId", body.getDialogId());
        return Map.of("code", 0, "data", data);
    }

    private void send(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new IllegalStateException("SSE connection closed", e);
        }
    }

    private String safeMessage(Exception e) {
        return StringUtils.hasText(e.getMessage()) ? e.getMessage() : "Agent 执行失败";
    }
}
