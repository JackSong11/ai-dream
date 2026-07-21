# 本地基础设施 Docker Compose 启动说明

Compose 只负责启动后端依赖的基础设施，不启动 Spring Boot 后端和 Vue 前端。后端、前端均由 IDE 或本地命令手动启动。

## 启动

首次启动先复制环境变量模板：

```bash
cp .env.example .env
docker compose up -d
```

查看基础设施状态：

```bash
docker compose ps
docker compose logs -f
```

服务入口：

- Elasticsearch 9.4.3：http://localhost:9200
- MinIO API：http://localhost:9000
- MinIO Console：http://localhost:9001
- MySQL：localhost:3306，数据库 `ai-dream`
- Redis：localhost:6379

所有基础设施健康后，在 IntelliJ IDEA 中启动 `DreamAppApplication`。当前 `application.yml` 使用 localhost 地址，可以直接连接 Compose 暴露的端口。

前端可在 `dream-frontend/dream-app-ui` 下手动启动：

```bash
npm run dev
```

开发环境默认登录账号为 `admin`，密码为 `123456`。该账号仅由 Compose 初始化脚本创建，生产环境必须删除或修改。

## 停止与清理

停止服务但保留数据：

```bash
docker compose down
```

删除容器及所有持久化数据，下一次启动会重新初始化数据库：

```bash
docker compose down -v
```

如果修改了 `dream-init.sql`，已有 MySQL volume 不会自动重新执行初始化脚本，需要先执行 `docker compose down -v`。

Elasticsearch 默认分配 1 GB 堆，建议 Docker Desktop 至少分配 4 GB 内存。可在 `.env` 中调整 `ES_JAVA_OPTS`。
