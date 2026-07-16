## 项目目录结构
ai-dream/
├── dream-frontend/                      # 🎨 前端工程
│   ├── dream-app-ui/                    # 用户侧智能助手前端 (Vue3)
│
└── dream-backend/                       # ☕ 后端 Maven 父工程

## 后端核心技术栈
Spring Boot 4.1 + Spring AI 2.0 + JDK21，Agent相关实现优先用Spring AI 2.0基建。

## 前端核心技术栈
用户侧 (dream-app-ui)：Vue 3 + Vite + TypeScript + TailwindCSS (智能助手流式传输 SSE 适配)