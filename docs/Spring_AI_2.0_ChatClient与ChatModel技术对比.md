# Spring AI 2.0：ChatClient 与 ChatModel 技术对比

> 基于 Spring AI 2.0.0 GA 官方参考文档、API 文档和 Spring 官方发布文章整理。  
> 资料访问日期：2026-07-17。

## 1. 结论

在 Spring AI 2.0 中，`ChatModel` 与 `ChatClient` 不是相互替代的两个同级 API，而是上下两层抽象：

- `ChatModel` 是模型层的统一、可移植接口，负责把 `Prompt` 发送给具体模型，并返回 `ChatResponse` 或 `Flux<ChatResponse>`。
- `ChatClient` 是构建在 `ChatModel` 之上的应用层客户端，负责组织 Prompt、运行 Advisor 链、处理工具调用循环、转换响应，并向业务代码提供 fluent API。

Spring AI 2.0.0 GA 发布说明明确将 `ChatClient` 定位为最常用的用户侧 API，将 `ChatModel` 定位为更底层的 building block。因此：

> 业务应用默认使用 `ChatClient`；只有在编写基础设施、框架适配层，或者确实需要直接控制 `Prompt → ChatResponse` 时，才直接使用 `ChatModel`。

## 2. ChatModel 是什么

`ChatModel` 是 Spring AI 对不同聊天模型提供商建立的统一模型接口。2.0 API 的核心定义可以概括为：

```java
public interface ChatModel extends Model<Prompt, ChatResponse>, StreamingChatModel {

    default String call(String message) { ... }

    ChatResponse call(Prompt prompt);

    default Flux<ChatResponse> stream(Prompt prompt) { ... }
}
```

其主要价值是屏蔽模型提供商差异：OpenAI、Anthropic、Google GenAI、Ollama、Mistral AI 等实现相同的 `ChatModel` 接口。上层代码可以围绕 `Prompt`、`ChatResponse` 和 `ChatOptions` 编程，而不必直接依赖某个提供商 SDK。

`ChatModel` 的职责集中在模型调用本身：

1. 接收已经构造完成的 `Prompt`。
2. 调用具体模型提供商。
3. 返回统一的 `ChatResponse`。
4. 通过 `stream(Prompt)` 返回流式 `ChatResponse`。
5. 暴露模型级 `ChatOptions`。

它解决的是“如何以统一方式调用不同模型”，而不是“业务应用如何完整地组织一次 AI 交互”。

## 3. ChatClient 是什么

`ChatClient` 是一个无状态的 fluent client。它内部持有并调用 `ChatModel`，但在模型调用前后增加了面向应用开发的请求构建、Advisor 编排和响应提取能力。

典型调用如下：

```java
ChatClient chatClient = chatClientBuilder.build();

String answer = chatClient.prompt()
        .system("你是企业知识助手")
        .user(userInput)
        .call()
        .content();
```

这里并没有绕过 `ChatModel`。实际关系是：

```text
业务代码
  → ChatClient
  → Advisor 链
  → ChatModel
  → 模型提供商 API
  → ChatResponse
  → Advisor 链
  → content / entity / ChatResponse
```

因此，`ChatClient` 是 `ChatModel` 的应用层门面和编排层，而 `ChatModel` 仍然是实际模型调用的底层执行者。

## 4. 为什么已经有 ChatModel，还需要 ChatClient

### 4.1 ChatModel 解决的是模型可移植性，不是应用交互编排

直接调用模型时，最基本的代码是：

```java
Prompt prompt = new Prompt(List.of(
        new SystemMessage("你是企业知识助手"),
        new UserMessage(question)
));

ChatResponse response = chatModel.call(prompt);
String content = response.getResult().getOutput().getText();
```

这对单次、简单调用已经足够。但业务功能增加后，应用还需要反复处理：

- system/user 消息和模板变量；
- 默认参数与单次调用参数的合并；
- 会话历史注入；
- RAG 检索上下文注入；
- 工具定义、模型工具请求、工具执行和结果回传；
- 日志、观测、安全或策略拦截；
- 模型文本到 Java 类型的转换；
- 输出校验与失败重试。

这些并不是模型提供商适配接口本身应该承担的职责。`ChatClient` 的出现，是为了在 `ChatModel` 之上提供统一的应用交互层。

### 4.2 应用需要统一的横切扩展点

如果只有 `ChatModel`，会话记忆、RAG、日志、策略校验和工具循环容易散落在各个 Service 中，或者每个团队各自再封装一层客户端。

`ChatClient` 通过有序的 Advisor 链提供统一扩展点。每个 Advisor 可以在请求进入模型前修改请求或上下文，也可以处理模型返回的响应；前一个 Advisor 的处理结果会传给后一个 Advisor。

```java
chatClient.prompt()
        .advisors(a -> a
                .advisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        QuestionAnswerAdvisor.builder(vectorStore).build())
                .param(ChatMemory.CONVERSATION_ID, conversationId))
        .user(userText)
        .call()
        .content();
```

这一层并不只是让 API 更流畅，而是给应用级能力提供了标准组合机制。

### 4.3 Spring AI 2.0 把 Agent 循环放到了 ChatClient 层

这是 2.0 中推荐 `ChatClient` 的最关键架构原因。

在 1.x 中，各个聊天模型实现内部包含自己的工具执行循环。应用可以调用工具，但很难拦截、包装或替换工具调用过程。

Spring AI 2.0 删除了各 `ChatModel` 内部的工具执行循环，把完整工具往返过程移到 `ToolCallingAdvisor`。`ChatClient` 会运行有序 Advisor 链，并允许 Advisor 循环重入后续调用链。官方说明同一机制可承载工具调用循环、结构化输出重试循环和评估循环。

因此，在 2.0 中：

- 使用 `ChatClient` 时，默认自动注册 `ToolCallingAdvisor`，除非显式关闭。
- 直接调用 `ChatModel` 时，模型可能返回工具调用请求，但不会在模型内部替应用执行完整工具循环。
- 若坚持直接使用 `ChatModel`，调用方需要自行用 `DefaultToolCallingManager` 控制循环。
- 官方推荐的工具调用路径是 `ChatClient + ToolCallingAdvisor`。

这意味着 `ChatClient` 已经成为 Spring AI 2.0 Agent 能力的主要组合入口。

## 5. ChatClient 比 ChatModel 多了什么

| 能力 | ChatClient | ChatModel |
|---|---|---|
| 抽象层级 | 应用层、用户侧 API | 模型层、低层 building block |
| Prompt 入口 | `prompt()`、`prompt(String)`、`prompt(Prompt)` | `call(Prompt)`、`stream(Prompt)` |
| fluent 消息构建 | 支持 system/user 链式构建 | 调用方自行构造 Message 和 Prompt |
| 模板变量 | 内置模板渲染与 `.param(...)` | 调用方自行渲染或构造消息 |
| 默认配置 | Builder 可定义默认 system、user、options、advisors、tools | 主要管理模型级 options |
| 调用级覆盖 | 可在每次 prompt 调用中覆盖或扩展默认配置 | 调用方自行创建完整 Prompt/Options |
| Advisor 链 | 原生支持有序 Advisor 编排和循环 | 不提供 ChatClient Advisor 编排入口 |
| ChatMemory | 可通过 Memory Advisor 注入历史 | 调用方自行读取历史并放入 Prompt |
| RAG | 可通过 RAG/VectorStore Advisor 组合 | 调用方自行检索并拼装 Prompt |
| 工具调用 | 2.0 默认注册 `ToolCallingAdvisor`，完成工具往返循环 | 2.0 已移除模型内部工具执行循环 |
| 结构化输出 | `.entity(...)` 返回 Java 类型 | 调用方自行生成约束、解析和处理错误 |
| 2.0 结构化校验 | 可配置 provider structured output、schema validation/self-correction | 调用方自行编排 |
| 字符串响应 | `.call().content()` | `call(String)` 或自行从 `ChatResponse` 提取 |
| 完整响应 | `.call().chatResponse()` | `call(Prompt)` 直接返回 `ChatResponse` |
| 流式调用 | `.stream()` fluent API | `stream(Prompt)` |
| 多模型基础 | 基于指定 `ChatModel` 构建客户端 | 本身就是具体模型调用入口 |

需要注意：`ChatClient` 增加的是请求编排与应用能力，而不是替代 `ChatModel` 的模型抽象。最终调用仍由 `ChatModel` 完成。

## 6. 直接使用 ChatModel 的缺点

以下缺点均来自两者官方职责边界和 2.0 架构变化，而不是说明 `ChatModel` 设计不合理。`ChatModel` 本来就是更低层的接口。

### 6.1 业务代码需要自行构造 Prompt

调用方需要显式创建 `SystemMessage`、`UserMessage`、消息列表、`Prompt` 和调用级选项。若大量业务服务直接依赖 `ChatModel`，这些装配代码会重复出现。

`ChatClient` 将这些操作收敛到 fluent request spec，同时仍允许通过 `prompt(Prompt)` 接收已经构造好的低层对象。

### 6.2 横切能力容易与业务代码耦合

直接使用 `ChatModel` 时，会话历史、检索、日志、策略校验等逻辑需要在调用前后手工执行。调用点越多，越容易形成不同的执行顺序和错误处理方式。

`ChatClient` 用 Advisor 链明确这些能力的组合顺序。但 Advisor 顺序本身也必须谨慎设计，因为一个 Advisor 修改后的请求和上下文会继续传给后续 Advisor。

### 6.3 工具调用不会自动完成完整循环

这是 Spring AI 2.0 与旧版本差异最大的地方。直接调用 `ChatModel` 时，不能再依赖具体模型实现内部执行工具。调用方要负责：

1. 检查模型响应中的工具调用请求；
2. 解析工具名称和参数；
3. 执行对应工具；
4. 把工具结果追加到对话；
5. 再次调用模型；
6. 持续循环直到模型给出最终响应或达到停止条件。

`ToolCallingAdvisor` 已经统一实现了这套往返过程，所以官方推荐通过 `ChatClient` 使用它。

### 6.4 结构化输出需要自行处理更多细节

`ChatClient.call().entity(...)` 封装了 schema 生成、提示约束、响应解析和 Java 类型转换。Spring AI 2.0 又在该入口增加了 provider-native structured output 与 schema validation/self-correction 配置。

直接使用 `ChatModel` 时，这些工作需要调用方自行组织，或者自行使用 Spring AI 更低层的 converter/schema 组件。

### 6.5 默认配置和单次配置的管理更繁琐

`ChatClient.Builder` 可以定义跨请求复用的默认 system/user、options、advisors 和 tools，并在单次调用中追加或覆盖。

Spring AI 2.0 官方还特别澄清：在 `ChatClient` 层，options builder 通常可以作为默认选项的局部定制器；到 `ChatModel` 层则需要形成完整有效的 options。直接使用 `ChatModel` 会让调用方更接近模型配置的完整装配责任。

### 6.6 响应提取代码更底层

如果只需要文本，`ChatClient` 可以直接使用：

```java
String content = chatClient.prompt(userInput)
        .call()
        .content();
```

如果需要元数据，仍可取得完整 `ChatResponse`：

```java
ChatResponse response = chatClient.prompt(userInput)
        .call()
        .chatResponse();
```

因此，使用 `ChatClient` 并不会失去对完整模型响应的访问；它只是同时提供了更高层的终结方式。

## 7. ChatClient 自身不解决什么

推荐使用 `ChatClient` 不意味着它自动解决所有状态和架构问题：

1. **它仍是无状态客户端。** 模型 API 不会自动记住前一次请求。使用 Memory Advisor 时，历史需要从 `ChatMemory` 获取并随本次请求发送。
2. **conversationId 仍需调用方提供。** 官方文档指出，使用 Memory Advisor 的每次调用都要通过 Advisor 参数提供 `ChatMemory.CONVERSATION_ID`，遗漏会抛出 `IllegalArgumentException`。
3. **Advisor 顺序需要设计。** 先做 Memory 再做 RAG，与先做 RAG 再做 Memory，可能产生不同上下文。
4. **结构化 entity 不适用于流式终结。** `.entity(...)` 需要完整响应，只能用于 `.call()`；`.stream()` 返回文本或响应流。
5. **多模型仍需显式配置。** 默认只自动配置一个 `ChatClient.Builder`。存在多个 `ChatModel` Bean 时，需要解决依赖歧义，并为不同模型构建对应客户端。
6. **手工构建多模型客户端时要保留自动配置能力。** 官方文档建议注入 `ChatClientBuilderConfigurer`，以保留已注册 customizer 和 observability 配置。

## 8. 代码对比

### 8.1 ChatClient：应用层推荐写法

```java
@Service
public class AssistantService {

    private final ChatClient chatClient;

    public AssistantService(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("你是企业知识助手")
                .build();
    }

    public String ask(String question) {
        return this.chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}
```

### 8.2 ChatModel：低层直接调用

```java
@Service
public class LowLevelAssistantService {

    private final ChatModel chatModel;

    public LowLevelAssistantService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String ask(String question) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("你是企业知识助手"),
                new UserMessage(question)
        ));

        ChatResponse response = this.chatModel.call(prompt);
        return response.getResult().getOutput().getText();
    }
}
```

两段代码在简单调用下差异不大。真正的差异会在加入 Memory、RAG、工具调用、结构化输出和统一拦截后显现：这些能力在 `ChatClient` 中有标准组合入口，而直接使用 `ChatModel` 时需要调用方自行编排。

## 9. 什么时候应该直接使用 ChatModel

以下场景直接依赖 `ChatModel` 是合理的：

- 编写一个新的应用层 AI Client 或框架组件；
- 构建自定义模型路由、负载均衡或 fallback 基础设施；
- 只需要原始 `Prompt → ChatResponse`，不需要 Advisor、工具循环或便捷响应转换；
- 需要以统一模型 SPI 为参数，让上层框架自行定义完整的交互语义；
- 研究或调试模型提供商的原始返回和元数据。

判断标准不是“调用是否简单”，而是当前代码属于哪一层：

- 如果代码在实现业务用例，应优先使用 `ChatClient`。
- 如果代码在实现 AI 基础设施或上层客户端，`ChatModel` 更合适。

## 10. 什么时候应该使用 ChatClient

以下是 Spring AI 2.0 中更适合 `ChatClient` 的场景：

| 场景 | 原因 |
|---|---|
| 普通问答 | fluent prompt 和便捷响应提取减少装配代码 |
| SSE 流式输出 | 提供 `.stream()`，同时可复用默认配置和 Advisor |
| 多轮会话 | Memory Advisor 是标准组合入口 |
| RAG | 可组合检索 Advisor 与其他 Advisor |
| 工具调用/Agent | 2.0 的 `ToolCallingAdvisor` 负责完整工具循环 |
| Java DTO 返回 | `.entity(...)` 提供类型转换与 2.0 结构化校验能力 |
| 日志、观测和策略控制 | Advisor 链提供统一前后处理位置 |
| 业务专用 AI 客户端 | Builder 可复用默认 system、options、tools 和 advisors |

## 11. 对本项目的建议

本项目使用 Spring Boot 4.1、Spring AI 2.0、JDK 21，且前端需要适配 SSE。建议采用以下边界：

```text
Controller / Application Service
            ↓
     业务专用 ChatClient
            ↓
Memory / RAG / ToolCalling / Validation Advisors
            ↓
         ChatModel
            ↓
     具体模型提供商
```

具体建议：

1. 业务 Service 默认注入 Spring Boot 自动配置的 `ChatClient.Builder`。
2. 按业务能力构建一个或多个长期复用的 `ChatClient`，不要在每次请求中重新组装所有默认配置。
3. SSE 接口使用 `chatClient.prompt(...).stream()`。
4. ChatMemory、RAG、工具调用和结构化校验通过 Advisor 链组织。
5. Controller 和普通业务 Service 不直接依赖 `OpenAiChatModel`、`AnthropicChatModel` 等具体实现。
6. 只有模型路由、底层适配或框架基础设施模块直接暴露 `ChatModel`。

## 12. 最终对比总结

`ChatModel` 是必要的，因为 Spring AI 需要一个统一、可替换的模型调用抽象；`ChatClient` 也是必要的，因为业务应用需要的不只是调用模型，还需要可组合的 Prompt 构建、上下文增强、工具循环、响应转换和横切处理。

二者的关系可以压缩为一句话：

> `ChatModel` 统一“怎么调用模型”，`ChatClient` 统一“应用怎么完成一次 AI 交互”。

Spring AI 2.0 推荐 `ChatClient` 的根本原因，不只是 fluent API 更简洁，而是 Advisor 链和工具调用循环已经成为 2.0 应用与 Agent 能力的主要架构承载点。

## 13. 官方资料

1. [Spring AI 2.0.0 GA Available Now](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)，2026-06-12。
2. [Chat Client API — Spring AI Reference](https://docs.spring.io/spring-ai/reference/api/chatclient.html)。
3. [Chat Model API — Spring AI Reference](https://docs.spring.io/spring-ai/reference/api/chatmodel.html)。
4. [ChatClient — Spring AI Parent 2.0.0 API](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/chat/client/ChatClient.html)。
5. [ChatModel — Spring AI Parent 2.0.0 API](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/chat/model/ChatModel.html)。
6. [Tool Calling in Spring AI 2.0: A Composable, Agentic Architecture](https://spring.io/blog/2026/06/15/spring-ai-composable-tool-calling/)，2026-06-15。
7. [Self-Correcting Structured Output in Spring AI 2.0](https://spring.io/blog/2026/06/23/spring-ai-self-correcting-structured-output/)，2026-06-23。
8. [Spring AI 2.0.0-RC1 Available Now](https://spring.io/blog/2026/06/06/spring-ai-2-0-0-RC1-available-now/)，用于核对工具执行循环从各 `ChatModel` 移除并迁移至 `ToolCallingAdvisor` 的变更。

