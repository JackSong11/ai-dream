package com.example.dream.web.controller;

import com.example.dream.common.context.UserContext;
import com.example.dream.common.vo.Result;
import com.example.dream.service.biz.AgentChatBizService;
import com.example.dream.service.biz.bo.chat.ChatAnswerBO;
import com.example.dream.service.biz.bo.chat.ChatCompletionReqBO;
import com.example.dream.service.biz.bo.chat.ChatMessageBO;
import com.example.dream.web.vo.chat.ChatAnswerVO;
import com.example.dream.web.vo.chat.ChatCompletionReqVO;
import com.example.dream.web.vo.chat.ChatMessageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Agent 的聊天补全接口（Spring AI 2.0 重写 nanobot process_direct）。
 *
 * <p>与 {@link ChatCompletionController} 并存的独立新入口，走 Agent 的模型 + 工具循环，
 * 复用同一套请求/响应 VO 与 SSE 协议，供前端替换调用。</p>
 *
 * <p>流式采用 Spring AI 2.0 响应式写法：Controller 直接返回
 * {@code Flux<ServerSentEvent<String>>}，由框架驱动 SSE 下发，无需手动 SseEmitter。</p>
 *
 * <ul>
 *   <li>POST /api/v1/agent/completions        非流式</li>
 *   <li>POST /api/v1/agent/completions/stream 流式 SSE</li>
 * </ul>
 *
 * @author dream
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentChatBizService agentChatBizService;

    /**
     * Agent 聊天补全（非流式）。
     */
    @PostMapping(value = "/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<ChatAnswerVO> completions(@RequestBody ChatCompletionReqVO reqVo) {
        String userId = UserContext.getUserId();
        log.info("[Agent补全] 收到非流式请求, userId={}, dialogId={}, convId={}",
                userId, reqVo.getDialogId(), reqVo.getConvId());
        ChatCompletionReqBO reqBo = toBO(reqVo);
        ChatAnswerBO answer = agentChatBizService.completion(reqBo, userId);
        return Result.success(toAnswerVO(answer));
    }

    /**
     * Agent 聊天补全（流式 SSE，Spring AI 2.0 响应式写法）。
     *
     * <p>业务层返回 {@code Flux<String>}（每个元素为一条 SSE data 的 JSON 字符串），
     * 这里包装为 {@code ServerSentEvent} 并由 Spring 驱动逐帧下发。</p>
     */
    @PostMapping(value = "/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamCompletions(@RequestBody ChatCompletionReqVO reqVo) {
        String userId = UserContext.getUserId();
        ChatCompletionReqBO reqBo = toBO(reqVo);
        return agentChatBizService.streamCompletion(reqBo, userId)
                .map(payload -> ServerSentEvent.builder(payload).build());
    }

    // ==================== 转换 ====================

    private ChatCompletionReqBO toBO(ChatCompletionReqVO vo) {
        ChatCompletionReqBO bo = new ChatCompletionReqBO();
        bo.setMessages(toMessageBOList(vo.getMessages()));
        bo.setDialogId(vo.getDialogId());
        bo.setConvId(vo.getConvId());
        bo.setLlmId(vo.getLlmId());
        if (vo.getPassAllHistoryMessages() != null) {
            bo.setPassAllHistoryMessages(vo.getPassAllHistoryMessages());
        }
        bo.setGenerationConfig(vo.getGenerationConfig());
        bo.setExtraParams(vo.getExtraParams());
        return bo;
    }

    private List<ChatMessageBO> toMessageBOList(List<ChatMessageVO> vos) {
        if (vos == null) {
            return null;
        }
        List<ChatMessageBO> list = new ArrayList<>(vos.size());
        for (ChatMessageVO vo : vos) {
            ChatMessageBO bo = new ChatMessageBO();
            bo.setRole(vo.getRole());
            bo.setContent(vo.getContent());
            bo.setId(vo.getId());
            bo.setFiles(vo.getFiles());
            list.add(bo);
        }
        return list;
    }

    private ChatAnswerVO toAnswerVO(ChatAnswerBO ans) {
        ChatAnswerVO vo = new ChatAnswerVO();
        if (ans == null) {
            return vo;
        }
        vo.setAnswer(ans.getAnswer());
        vo.setReference(ans.getReference());
        vo.setId(ans.getId());
        vo.setConvId(ans.getConvId());
        vo.setDialogId(ans.getDialogId());
        return vo;
    }
}