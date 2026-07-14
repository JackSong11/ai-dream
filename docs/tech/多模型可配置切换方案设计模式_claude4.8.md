# 多模型可配置 / 可切换 / 可调用方案设计模式说明

> 目录：`dream-service/src/main/java/com/example/dream/service/core/ai`
> 目标：支持**多模型可配置**、**运行时可切换**、**统一可调用**，并做到「新增模型改配置、新增协议加类」，符合开闭原则。

## 一、整体架构

```
应用配置(application.yml: dream.ai)
        │  绑定
        ▼
DreamAiProperties ── providers ──▶ ProviderProperties ── models ──▶ ModelProperties
        │
        ▼
ChatModelRegistry (注册中心 / 路由中心)
        │ 遍历配置, 逐个构建
        ▼
ChatModelFactory (工厂 + 路由)
        │ 按 provider.type 分派
        ▼
ChatModelProvider (策略接口)
        └── OpenAiCompatibleChatModelProvider (openai-compatible 策略实现)
                │ 构建
                ▼
        ChatModel ──包装──▶ ChatClient (缓存到 Registry, 供业务按 modelKey 调用)
```

核心分层：
- **配置层**：`DreamAiProperties` / `ProviderProperties` / `ModelProperties` —— 「供应商 → 模型」两层结构，同一网关多模型共享连接参数。
- **策略层**：`ChatModelProvider` 及其实现 —— 每种协议族一个实现。
- **工厂/路由层**：`ChatModelFactory` —— 按协议类型路由到对应策略。
- **注册/门面层**：`ChatModelRegistry` —— 启动预建、缓存、按 key 路由、热更新。

---

## 二、使用的设计模式

### 1. 策略模式 (Strategy Pattern)

- **角色**：
  - 策略接口：[`ChatModelProvider`](dream-backend/dream-service/src/main/java/com/example/dream/service/core/ai/provider/ChatModelProvider.java:16)
  - 具体策略：[`OpenAiCompatibleChatModelProvider`](dream-backend/dream-service/src/main/java/com/example/dream/service/core/ai/provider/OpenAiCompatibleChatModelProvider.java:22)
- **解决的问题**：不同模型协议族（openai-compatible、未来的 ollama、anthropic 原生等）构建 `ChatModel` 的方式不同。将「如何构建」抽象为策略，各协议独立封装。
- **收益**：新增协议只需新增一个实现类交给 Spring 管理，**无需修改任何已有代码**（开闭原则 OCP）。

### 2. 工厂模式 (Factory Pattern)

- **角色**：[`ChatModelFactory`](dream-backend/dream-service/src/main/java/com/example/dream/service/core/ai/factory/ChatModelFactory.java:23)
- **实现**：构造时由 Spring 注入所有 `ChatModelProvider` 实现，以 `type()` 建立 `Map<String, ChatModelProvider>` 索引；`create()` 根据 `provider.getType()` 路由到对应策略并委托构建。
- **解决的问题**：屏蔽「模型对象创建」的复杂性与协议差异，调用方只需给出配置即可拿到 `ChatModel`。
- **说明**：工厂在此同时承担了**协议路由**职责（策略选择器），是「工厂 + 策略注册表」的结合。

### 3. 注册表模式 / 门面模式 (Registry / Facade Pattern)

- **角色**：[`ChatModelRegistry`](dream-backend/dream-service/src/main/java/com/example/dream/service/core/ai/registry/ChatModelRegistry.java:29)
- **实现**：
  - 启动时 `@PostConstruct` → `refresh()` 遍历所有供应商与模型，通过工厂构建并缓存 `Map<modelKey, ChatClient>`。
  - 对外暴露 [`getClient(modelKey)`](dream-backend/dream-service/src/main/java/com/example/dream/service/core/ai/registry/ChatModelRegistry.java:118)、`listModels()`、`exists()` 等统一入口。
- **解决的问题**：为业务侧提供**单一的、稳定的调用门面**，隐藏底层多模型的构建与选择细节，实现「**可切换**」——传入不同 `modelKey` 即切换模型，为空则走默认模型。

### 4. 建造者模式 (Builder Pattern)

- **体现**：`OpenAiChatOptions.builder()...build()`、`OpenAiChatModel.builder()...build()`、`ChatClient.builder(...).build()`（Spring AI 提供）。
- **解决的问题**：模型参数（baseUrl、apiKey、model、temperature、maxTokens）众多且部分可选，通过链式建造清晰组装，支持可选项按需设置。

### 5. 缓存/享元思想 + 单例 (Cache / Singleton)

- **体现**：`ChatModelRegistry` 用 `ConcurrentHashMap` 缓存已构建的 `ChatClient`，避免每次调用重复创建重量级客户端；各组件均为 Spring 单例 Bean。
- **收益**：性能与线程安全兼顾（`ConcurrentHashMap` + `volatile defaultModelKey` + `synchronized refresh()`）。

### 6. 依赖注入 / 控制反转 (DI / IoC)

- **体现**：`ChatModelFactory` 构造器注入 `List<ChatModelProvider>` 自动收集所有策略；`ChatModelRegistry` 注入 `DreamAiProperties` 与 `ChatModelFactory`。
- **收益**：策略的「自动发现与装配」，是策略+工厂能做到开闭的关键支撑。

---

## 三、配置驱动的可扩展性总结

| 扩展场景 | 操作方式 | 是否改代码 |
| --- | --- | --- |
| 同一网关新增一个模型 | 在对应 `provider.models` 追加 `key/name/model` | 否 |
| 新增一个供应商(同协议) | 在 `dream.ai.providers` 追加 provider 配置 | 否 |
| 新增一种全新协议 | 新增 `ChatModelProvider` 实现类并注册为 Bean | 是(仅加类) |
| 切换默认模型 | `primary` 标记 或 `dream.ai.defaultModel` | 否 |
| 运行时切换模型 | 调用 `getClient(modelKey)` 传入不同 key | 否 |

**默认模型优先级**：`primary` 标记 > 配置 `defaultModel` > 第一个注册的模型。

**参数级联**：连接参数 / 温度均支持「模型级覆盖 > 供应商级」。

---

## 四、设计模式协作关系

```
配置 (Configuration)
   └─▶ Registry(门面/注册表) —— 统一调用入口, 缓存, 路由
          └─▶ Factory(工厂) —— 按 type 选择策略
                 └─▶ Strategy(策略) —— 各协议构建逻辑
                        └─▶ Builder —— 组装 ChatModel / ChatClient
```

- **策略 + 工厂**：解决「多协议、可扩展」——回答**可配置/可扩展**。
- **注册表 + 缓存**：解决「统一调用、运行时切换」——回答**可切换/可调用**。
- **Builder + DI**：作为底层支撑，简化对象构建与组件装配。