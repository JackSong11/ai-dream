# AI 多模型可配置/可切换/可调用 — 设计模式分析

## 一、背景

`dream-service/core/ai` 模块解决了以下核心问题：

- **多模型可配置**：通过 YAML 声明式配置多个供应商与模型，无需改代码即可增删模型
- **模型可切换**：运行时通过 `modelKey` 路由到不同模型，支持默认模型兜底
- **多模型可调用**：统一 `ChatClient` 接口，业务层无感知底层协议差异

---

## 二、整体架构

```
┌─────────────────────────────────────────────────────┐
│                   配置层 (config)                      │
│  DreamAiProperties → ProviderProperties → ModelProperties │
│         (dream.ai)        (供应商)           (模型)      │
└──────────────────────┬──────────────────────────────┘
                       │ 启动时读取
┌──────────────────────▼──────────────────────────────┐
│              注册表 (registry)                        │
│           ChatModelRegistry                          │
│   ┌──────────────────────────────────┐               │
│   │  clientCache: Map<key, ChatClient>│               │
│   │  modelMetaCache: Map<key, Model>  │               │
│   └──────────────┬───────────────────┘               │
│                  │ 构建时委托                          │
└──────────────────┼──────────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────────┐
│              工厂 (factory)                           │
│           ChatModelFactory                           │
│   ┌──────────────────────────────────┐               │
│   │  providerMap: Map<type, Provider> │               │
│   └──────────────┬───────────────────┘               │
│                  │ 路由到具体策略                      │
└──────────────────┼──────────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────────┐
│           策略提供者 (provider)                        │
│  ┌─────────────────────────────────────┐             │
│  │  ChatModelProvider (接口)            │             │
│  ├─────────────────────────────────────┤             │
│  │  OpenAiCompatibleChatModelProvider   │             │
│  │  (未来: OllamaProvider, ...)         │             │
│  └─────────────────────────────────────┘             │
└─────────────────────────────────────────────────────┘
```

---

## 三、设计模式详解

### 1. 策略模式 (Strategy Pattern)

**涉及类**：[`ChatModelProvider`](dream-backend/dream-service/src/main/java/com/example/dream/service/core/ai/provider/ChatModelProvider.java) 接口 + 各实现类

**结构**：

| 角色 | 类 | 职责 |
|------|-----|------|
| 策略接口 | `ChatModelProvider` | 定义 `type()` 与 `create()` 两个方法 |
| 具体策略 | `OpenAiCompatibleChatModelProvider` | 实现 OpenAI 兼容协议的模型构建 |
| 上下文 | `ChatModelFactory` | 持有策略映射表，按 `type` 路由到对应策略 |

**解决的问题**：不同模型供应商使用不同协议（OpenAI 兼容、Ollama、Anthropic 原生等），需要一种方式在运行时根据协议类型选择构建逻辑，而非硬编码 if-else。

**实现要点**：
- `ChatModelProvider` 接口声明 `type()` 返回协议标识，`create()` 执行具体构建
- `OpenAiCompatibleChatModelProvider` 用 `TYPE = "openai-compatible"` 标识自己
- 工厂通过 `providerMap.get(type)` 路由，新增协议只需新增实现类

**扩展性**：新增 Ollama 协议只需添加 `OllamaChatModelProvider implements ChatModelProvider`，标注 `@Component`，无需修改任何已有代码。

---

### 2. 工厂方法模式 (Factory Method Pattern)

**涉及类**：[`ChatModelFactory`](dream-backend/dream-service/src/main/java/com/example/dream/service/core/ai/factory/ChatModelFactory.java)

**结构**：

| 角色 | 类 | 职责 |
|------|-----|------|
| 工厂 | `ChatModelFactory` | 根据供应商 type 路由到对应 Provider，构建 `ChatModel` |
| 产品接口 | `ChatModel` (Spring AI) | 工厂产出的统一抽象 |
| 具体产品 | `OpenAiChatModel` 等 | Spring AI 提供的具体模型实现 |

**解决的问题**：`ChatModel` 实例的创建涉及复杂的参数拼装（baseUrl、apiKey、model、temperature 的级联覆盖），需要封装创建过程，使调用方无需关心构建细节。

**实现要点**：
- `create(ProviderProperties, ModelProperties)` 封装了完整的路由 + 构建逻辑
- 调用方（`ChatModelRegistry`）只与 `ChatModelFactory` 交互，不直接接触任何 `ChatModelProvider`
- 构造器注入 `List<ChatModelProvider>`，Spring 自动收集所有策略实现

**与策略模式的协作**：工厂内部使用策略模式做路由，工厂本身负责"选用哪个策略"的决策，策略负责"如何构建"的具体逻辑。两者组合实现了 **策略路由 + 创建封装**。

---

### 3. 注册表模式 / 服务定位器模式 (Registry / Service Locator Pattern)

**涉及类**：[`ChatModelRegistry`](dream-backend/dream-service/src/main/java/com/example/dream/service/core/ai/registry/ChatModelRegistry.java)

**结构**：

| 角色 | 类/字段 | 职责 |
|------|---------|------|
| 注册表 | `ChatModelRegistry` | 全局模型实例的注册、查询、路由 |
| 缓存 | `clientCache` / `modelMetaCache` | `ConcurrentHashMap<modelKey, ChatClient/ModelProperties>` |
| 默认路由 | `defaultModelKey` | 支持三级优先级：primary > defaultModel > 第一个 |

**解决的问题**：业务层需要通过简洁的 `modelKey` 获取可用的 `ChatClient`，而不关心模型是如何构建、配置从何而来。

**实现要点**：
- `@PostConstruct` 启动时自动调用 `refresh()` 完成所有模型的注册
- `getClient(modelKey)` 支持空值兜底到默认模型
- `ConcurrentHashMap` 保证线程安全
- `refresh()` 方法支持热更新（配置变更后可重新加载）
- `listModels()` 提供模型列表，支持前端下拉展示

**与工厂的关系**：注册表是工厂的上层编排者——它遍历配置，调用工厂构建每个模型，再缓存结果。注册表负责"有哪些模型"，工厂负责"如何构建模型"。

---

### 4. 模板方法模式 (Template Method Pattern) — 变体

**涉及类**：[`OpenAiCompatibleChatModelProvider.create()`](dream-backend/dream-service/src/main/java/com/example/dream/service/core/ai/provider/OpenAiCompatibleChatModelProvider.java:40) 中的参数级联逻辑

**结构**：

```
create() 固定流程：
  1. baseUrl  级联：model.baseUrl > provider.baseUrl
  2. apiKey   级联：model.apiKey  > provider.apiKey
  3. temperature 级联：model.temperature > provider.temperature
  4. 构建 OpenAiChatOptions
  5. 构建 OpenAiChatModel
```

**解决的问题**：不同层级的配置存在优先级覆盖关系（模型级 > 供应商级），需要统一的参数解析流程。

**实现要点**：
- 每个 `ChatModelProvider` 实现类内部的 `create()` 方法遵循固定的步骤顺序
- 参数级联逻辑（模型级覆盖供应商级）是固定的"模板骨架"
- 不同协议的 Provider 在步骤 4、5 的具体构建方式不同（例如 Ollama 会用 `OllamaChatModel`）

**说明**：这不是经典的模板方法（没有抽象基类定义模板），而是接口约束下的 **隐式模板**——所有 Provider 的 `create()` 方法都遵循"解析参数 → 构建选项 → 构建模型"的固定流程。

---

### 5. 建造者模式 (Builder Pattern) — 使用侧

**涉及类**：[`OpenAiCompatibleChatModelProvider.create()`](dream-backend/dream-service/src/main/java/com/example/dream/service/core/ai/provider/OpenAiCompatibleChatModelProvider.java:40) 中对 Spring AI Builder 的使用

**结构**：

```java
OpenAiChatOptions.builder()
    .baseUrl(baseUrl)
    .apiKey(apiKey)
    .model(model.getModel())
    .temperature(temperature)
    .build();

OpenAiChatModel.builder()
    .options(optionsBuilder.build())
    .build();
```

**解决的问题**：`ChatModel` 的构建参数多且可选（maxTokens 可为 null），需要灵活拼装。

**实现要点**：
- 使用 Spring AI 内置的 Builder，按需设置参数
- `if (model.getMaxTokens() != null)` 条件式设置，避免传入无效值
- 将复杂的构造函数参数列表转化为流畅的链式调用

---

## 四、模式协作关系

```
业务层调用
    │
    ▼
ChatModelRegistry ──────── 注册表模式：按 key 路由到 ChatClient
    │
    │ getClient(key)
    │
    ▼
ChatModelFactory ───────── 工厂模式：按 type 路由到 Provider
    │
    │ create(provider, model)
    │
    ▼
ChatModelProvider ───────── 策略模式：不同协议不同构建逻辑
    │
    │ 隐式模板方法：参数级联 → 构建选项 → 构建模型
    │
    ▼
OpenAiChatModel.builder() ─ 建造者模式：灵活拼装复杂对象
```

| 模式 | 解决的问题 | 核心收益 |
|------|-----------|---------|
| 策略模式 | 协议差异 | 新增协议零改动已有代码 |
| 工厂模式 | 创建封装 | 调用方不接触构建细节 |
| 注册表模式 | 实例管理与路由 | 按 key 获取，支持默认兜底与热更新 |
| 模板方法(变体) | 参数级联流程 | 统一覆盖优先级，避免散落的 if-else |
| 建造者模式 | 复杂对象构建 | 可选参数灵活拼装 |

---

## 五、开闭原则 (OCP) 体现

整个模块严格遵循 **对扩展开放，对修改关闭**：

| 扩展场景 | 需要做的 | 不需要改的 |
|---------|---------|-----------|
| 新增同一供应商下的模型 | YAML 中追加 model 配置 | 任何 Java 代码 |
| 新增同协议的新供应商 | YAML 中追加 provider 配置 | 任何 Java 代码 |
| 新增一种全新协议 | 新增一个 `ChatModelProvider` 实现类 | Factory、Registry、Config |
| 修改模型参数（温度/Token） | YAML 中修改对应字段 | 任何 Java 代码 |

---

## 六、类职责速查

| 类 | 包 | 核心职责 |
|----|-----|---------|
| `DreamAiProperties` | config | 绑定 `dream.ai` 前缀配置 |
| `ProviderProperties` | config | 供应商配置（URL/密钥/协议类型） |
| `ModelProperties` | config | 模型配置（key/model名/温度/覆盖参数） |
| `DreamAiConfiguration` | config | 启用配置绑定 |
| `ChatModelFactory` | factory | 按 type 路由到 Provider 构建 ChatModel |
| `ChatModelProvider` | provider | 策略接口，定义协议类型与构建方法 |
| `OpenAiCompatibleChatModelProvider` | provider | OpenAI 兼容协议的具体策略实现 |
| `ChatModelRegistry` | registry | 模型注册、缓存、路由、热更新 |