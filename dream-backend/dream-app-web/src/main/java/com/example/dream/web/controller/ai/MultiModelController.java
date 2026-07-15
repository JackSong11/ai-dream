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

}