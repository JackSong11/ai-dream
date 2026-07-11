package com.example.dream.web.controller;

import com.example.dream.common.context.UserContext;
import com.example.dream.common.vo.Result;
import com.example.dream.service.biz.ChatCompletionBizService;
import com.example.dream.service.biz.bo.chat.ChatAnswerBO;
import com.example.dream.service.biz.bo.chat.ChatCompletionReqBO;
import com.example.dream.service.biz.bo.chat.ChatMessageBO;
import com.example.dream.web.vo.chat.ChatAnswerVO;
import com.example.dream.web.vo.chat.ChatCompletionReqVO;
import com.example.dream.web.vo.chat.ChatMessageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 聊天补全接口。
 *
 * <p>对应 RagFlow chat_api.py：POST /chat/completions（session_completion）。
 * 支持流式（SSE）与非流式两种模式，鉴权使用当前登录用户 userId。</p>
 *
 * @author dream
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatCompletionController {

    private final ChatCompletionBizService chatCompletionBizService;

    /**
     * 使用虚拟线程执行 SSE 生成任务，避免阻塞 Servlet 容器线程。
     */
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 聊天补全（非流式）。
     *
     * <p>对应 session_completion 的 stream_mode=False 分支，返回一次性 JSON 结果。</p>
     *
     * @param reqVo 请求体
     * @return 统一包装的结构化答案
     */
    @PostMapping(value = "/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<ChatAnswerVO> completions(@RequestBody ChatCompletionReqVO reqVo) {
        String userId = UserContext.getUserId();
        ChatCompletionReqBO reqBo = toBO(reqVo);
        ChatAnswerBO answer = chatCompletionBizService.completion(reqBo, userId);
        return Result.success(toAnswerVO(answer));
    }

    /**
     * 聊天补全（流式 SSE）。
     *
     * <p>对应 session_completion 的 stream_mode=True 分支，返回 SSE 事件流。
     * SSE 为持续推送的事件流协议，不适用统一 Result 包装。</p>
     *
     * @param reqVo 请求体
     * @return {@link SseEmitter} 流式返回
     */
    @PostMapping(value = "/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCompletions(@RequestBody ChatCompletionReqVO reqVo) {
        String userId = UserContext.getUserId();
        ChatCompletionReqBO reqBo = toBO(reqVo);

        SseEmitter emitter = new SseEmitter(0L);
        sseExecutor.execute(() -> {
            try {
                chatCompletionBizService.streamCompletion(reqBo, userId, payload -> {
                    try {
                        // SseEmitter.data() 输出即为 "data:<payload>\n\n"，与 RagFlow 协议一致
                        emitter.send(SseEmitter.event().data(payload));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                log.error("[ChatCompletion] SSE stream failed", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /**
     * 请求 VO 转 BO。
     */
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

    /**
     * 答案 BO 转输出 VO（字段名对齐 RagFlow）。
     */
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
        vo.setAudioBinary(ans.getAudioBinary());
        return vo;
    }
}