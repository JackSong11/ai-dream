# ai-dream (项目根目录)

欢迎使用 **ai-dream**。本项目是一个基于主流技术栈构建的智能助手与后台管理系统，采用模块化、纵向拆分与横向共享相结合的微服务/多模块工程架构。

---

## 📂 项目目录结构

```text
ai-dream/
├── dream-frontend/                      # 🎨 前端工程
│   ├── dream-app-ui/                    # 用户侧智能助手前端 (Vue3 / React)
│   └── dream-admin-ui/                  # ⚙️ 后台管理系统前端 (Vue3 + Element-Plus)
│
└── dream-backend/                       # ☕ 后端 Maven 父工程
    ├── pom.xml                          # 父 POM 配置
    ├── dream-api/                       # 🔗 对外契约层 (服务接口定义，如dubbo对外提供的接口定义，参数定义，数据传输格式定义等)
    ├── dream-common/                    # 🛠️ 全局公共底座 (通用工具类、应用常量、通用方法)
    │
    │  [ 入口层 / 启动层 ] -------------- (纵向拆分，各自独立打包部署)
    ├── dream-app-web/                   # 📱 用户侧 WEB 层 (处理 HTTP / SSE / WS 请求)
    ├── dream-app-starter/               # 🚀 用户侧 Spring Boot 启动入口：启动层，包装springboot的starter和一些注册服务的配置
    │
    ├── dream-admin-web/                 # ⚙️ 后台管理侧 WEB 层 (知识库管理、大模型配置等 Controller)
    ├── dream-admin-starter/             # 🚀 后台管理侧 Spring Boot 启动入口 (独立打包可执行 JAR)：启动层，包装springboot的starter和一些注册服务的配置
    │
    │  [ 核心业务层 ] ------------------ (统一 Maven 模块，通过包结构进行领域分层)
    ├── dream-service/                   # 🧠 核心业务模块
    │     ├── biz/                     # 🚀 业务流程编排层 (领域业务，提供核心领域服务，可允许打包jar后外部引用，与client的区别是，最终领域服务是本地内调用实现，非Remoting实现)
    │     └── core/                    # 🎯 领域核心能力层 (领域内部核心服务，提供核心领域服务)
    │
    │  [ 基础设施支撑层 ] -------------- (横向共享模块)
    ├── dream-dal/                       # 💾 持久层 (MyBatis 实现，包含 Mapper 接口与 PO 对象)
    ├── dream-integration/               # 🔌 外部集成层 (外部第三方服务调用，如调用外部钉钉，邮件，SDK等)
    └── dream-processor/                 # ⚙️ 异步处理层 (回调入口、MQ 消费、DTS 订阅、Job 定时任务、工作流等)
```
## 📐 对象说明
```text
BO（Business Object）：业务对象，与领域设计的实体对应，在 service 层使用。
DTO（Data Transfer Object）：数据传输对象，Dubbo 等远程调用接口层（client，facade）入参出参使用。
PO（Persistent Object）：持久化对象，dal 层使用。
VO（View Object）：值对象/视图对象，在 web 层使用，直接对接前端接口。

注： 各种对象通常不允许直接在其他层次使用，一般是进行对象转换后使用。各对象严禁跨层使用！
```