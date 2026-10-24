# de-screenshot

DataEase 截图服务（自研、开源），供定时报告、阈值告警生成仪表板 / 数据大屏截图与 PDF。
接口与 `dataease.playwright-server` 配置一致，可直接替代 `de-playwright-api`。

## 部署

安装包默认通过 `docker-compose-playwright.yml` 启动本服务（首次启动自动构建镜像 `dataease/de-screenshot:1.0.0`）。
DataEase 配置：

```yaml
dataease:
  playwright-server: http://de-playwright-api:3000/screenshot
  report:
    base-url: http://dataease:8100/   # 截图服务访问 DataEase 前端的地址（默认即此值）
```

手动构建：`docker build -t dataease/de-screenshot:1.0.0 .`

本地运行（需已安装 Playwright 与 Chromium）：`npm install && PORT=3000 node server.js`

## 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PORT` | `3000` | 监听端口 |
| `CONCURRENCY` | `2` | 同时截图数 |
| `QUIET_MS` | `2000` | 页面网络请求全部结束后需保持安静的毫秒数 |
| `CHROMIUM_PATH` | 空 | 指定 Chromium 可执行文件 |

## 接口

`POST /screenshot`，JSON（字段均为字符串）：`url`、`type`（`jpeg`/`pdf`）、`width`、`height`、`waitTime`、`renderTime`、
`timeout`（秒）、`localStorage`（JSON 字符串，页面脚本执行前写入目标站点的 localStorage，用于携带登录信息）。

响应 `multipart/form-data`：`name="metadata"` 为 JSON 元数据（失败时包含 `error`），`name="image"` 为文件内容。

`GET /health` 健康检查。
