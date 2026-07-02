package com.example.dream.app.web.controller;

import com.example.dream.common.dto.ActorFilms;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
class MyController {

    // 1. 直接注入 Builder 属性
    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private OpenAiChatModel chatModel;

    @GetMapping("/ai")
    String generation(String userInput) {
        return this.chatClientBuilder.defaultSystem("你是一个AI助手，完成用户任务").build().prompt()
                .user(userInput)
                .call()
                .content();
    }

    @GetMapping("/ai/generate")
    public Map<String, String> generate(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {

//        ChatResponse chatResponse = chatClientBuilder.build().prompt()
//                .user("Tell me a joke")
//                .call()
//                .chatResponse();

//        ActorFilms actorFilms = chatClientBuilder.build().prompt()
//                .user("Generate the filmography for a random actor.")
//                .call()
//                .entity(ActorFilms.class);

//        ActorFilms actorFilms = chatClientBuilder.build().prompt()
//                .user("Generate the filmography for a random actor.")
//                .call()
//                .entity(new ParameterizedTypeReference<ActorFilms.class>() {});

//        ActorFilms actorFilms = chatClientBuilder.build().prompt()
//                .user("Generate the filmography for a random actor.")
//                .call()
//                .entity(ActorFilms.class, spec -> spec.useProviderStructuredOutput());
//
//        ActorFilms actorFilms = chatClientBuilder.build().prompt()
//                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
//                .user("Generate the filmography for a random actor.")
//                .call()
//                .entity(ActorFilms.class);
//        ActorFilms actorFilms = chatClientBuilder.build().prompt()
//                .user("Generate the filmography for a random actor.")
//                .call()
//                .entity(ActorFilms.class, spec -> spec.validateSchema());

//        // 2. 创建一个 BeanOutputConverter，它是 StructuredOutputConverter 的实现类
//        // 这里传入 ActorFilms.class，让转换器知道要把 AI 的结果转成什么对象
//        BeanOutputConverter<ActorFilms> converter = new BeanOutputConverter<>(ActorFilms.class);
//
//        // 3. 使用带有 StructuredOutputConverter 参数的 entity() 方法
//        ActorFilms actorFilms = chatClientBuilder.build().prompt()
//                // 核心原理：converter.getFormat() 会自动把"请按以下JSON格式返回..."的提示词追加到你的 user message 后面
//                .user("Generate the filmography for a random actor.")
//                .call()
//                .entity(converter); // <-- 这里触发了你想要的那个重载方法
        // 2. 创建一个 BeanOutputConverter，它是 StructuredOutputConverter 的实现类
        // 这里传入 ActorFilms.class，让转换器知道要把 AI 的结果转成什么对象
        BeanOutputConverter<ActorFilms> converter = new BeanOutputConverter<>(ActorFilms.class);

        // 3. 使用带有 StructuredOutputConverter 参数的 entity() 方法
        ActorFilms actorFilms = chatClientBuilder.build().prompt()
                // 核心原理：converter.getFormat() 会自动把"请按以下JSON格式返回..."的提示词追加到你的 user message 后面
                .user("Generate the filmography for a random actor.")
                .call()
                .entity(converter, spec -> spec.useProviderStructuredOutput()); // <-- 这里触发了你想要的那个重载方法
        System.out.println(actorFilms);
//        return Map.of("generation", this.chatModel.call(message));
        return null;

    }

    // 核心：必须指定 produces = MediaType.TEXT_EVENT_STREAM_VALUE
    @GetMapping(value = "/joke-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> getJokeStream() {
        return chatClientBuilder.build().prompt()
                .user("Tell me a joke")
                .stream()
                .content();
    }
}