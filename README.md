# DYA PLATFORM

抖音账号采集平台：一站式完成「AI 短剧采集 → 数据看板 → 审核 → 上传」。

## 功能概览

- **实时采集**：平台通过 CDP 接管专用 Chrome，精选频道与搜索结果两条采集线可同时运行，画面实时回传，不需要浏览器插件
- **搜索采集**：推荐词库一次 10 个词、5 分钟一批随机轮换；词按出货量四色标记（绿/蓝/黄/红）；停止下滑 3 分钟无操作自动出词并开始下滑，连续 1 分钟没有新内容自动换词
- **关键词分析**：按周统计每个词的采集量（aweme_id 去重）、环比、作者数、上传转化与跨词重复作者
- **视频数据看板**：采集入库、清洗状态、上传状态统一查看
- **账号与权限**：管理员 / 采集员 / 上传员三种角色；采集员可提交新词，由管理员审批入词库
- **采集终端管理**：终端 IP 自动上报、抖音扫码登录态持久化、断线自动重连

## 技术栈

- 后端：Spring Boot 3.2 / Java 17 / JdbcTemplate / Flyway / MySQL 8
- 前端：Vue 3 / Element Plus / Vite
- 部署：Docker Compose（nginx + backend + mysql），可选 systemd worker

## 目录结构

```
platform/
├── backend/   后端 API、CDP 会话与 WebSocket、数据入库
├── web/       Vue 3 前端（采集工作区、看板、关键词分析、管理）
├── worker/    定时判定 worker
├── deploy/    Dockerfile、docker-compose、nginx 配置、采集终端安装包
├── ops/       备份脚本与 systemd 单元
└── scripts/   本机采集启动脚本、CDP bridge
```

## 本地启动

后端（需要本地 MySQL，或通过环境变量指定）：

```bash
cd platform/backend
mvn spring-boot:run
```

常用环境变量：`DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER`、`DB_PASSWORD`、`JWT_SECRET`。

前端：

```bash
cd platform/web
npm install
npm run dev   # http://localhost:5173，/api 代理到 127.0.0.1:8080
```

## 部署

```bash
cd platform/deploy
cp .env.example .env   # 必填 MYSQL_ROOT_PASSWORD、DB_PASSWORD、JWT_SECRET
docker compose up -d --build
```

入口：`http://<服务器地址>:${WEB_PORT}`（默认 19203）。

## 采集终端

每台采集电脑：

1. 启动 Chrome 并开启 9222 远程调试端口
2. 运行 `platform/deploy/collector-kit` 里的代理（默认 9223）
3. 平台「实时采集」里选择对应终端，扫码登录一次抖音即可长期复用

## 说明

`.env` 含数据库密码与 JWT 密钥，已被 `.gitignore` 排除，不要提交到仓库。
