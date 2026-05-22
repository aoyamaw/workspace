## Why

需要开发一个完整的智能天气预报 Web 应用，让用户可以快速查询城市天气，并通过 AI 助手理解天气影响、出行建议和异常天气风险。系统还需要支持收藏城市和天气推送，提升用户对常用城市天气变化的感知能力。

## What Changes

- 新增城市天气查询能力，支持用户输入城市名称并查看天气、湿度、风速、天气状态等核心信息。
- 新增 AI 天气助手能力，回答天气相关问题，并基于当前城市天气提供建议。
- 新增收藏城市能力，用户可以保存、查看、移除常用城市。
- 新增天气推送能力，支持针对收藏城市接收天气变化、恶劣天气或每日天气摘要通知。
- 新增用户数据归属约束，收藏城市、通知订阅和 AI 问答记录必须关联登录用户或匿名会话身份。
- 新增 Web 应用体验约束，覆盖响应式布局、加载状态、错误提示、空状态和可访问性。
- 明确技术架构：前端使用 Vue 3，后端使用 Spring Boot，并通过 WebFlux/Reactor Netty 提供 Netty 运行能力，数据库使用 PostgreSQL。

## Capabilities

### New Capabilities
- `city-weather-query`: 城市天气查询、城市解析、天气数据展示和异常处理。
- `ai-weather-assistant`: 天气相关 AI 问答、上下文理解、回答边界和安全限制。
- `favorite-city-notifications`: 收藏城市管理、通知订阅、天气推送和权限处理。
- `weather-web-experience`: Web 页面结构、响应式体验、状态反馈和基础可访问性。

### Modified Capabilities

无。

## Impact

- 影响前端页面、天气数据接口、城市搜索或地理编码接口、AI 助手接口、收藏数据存储、通知订阅、推送服务和测试验收。
- 前端采用 Vue 3 + Vite；后端采用 Spring Boot + WebFlux/Reactor Netty；数据持久化采用 PostgreSQL。
- 需要接入至少一个可靠天气数据源；AI 助手不得编造实时天气，应基于系统已获取的天气数据或明确说明无法获取实时信息。
- 推送功能区分浏览器 Web Push 后台通知和 SSE/WebSocket 应用内实时更新，并依赖浏览器通知权限、HTTPS、Service Worker、服务端定时任务或第三方推送服务。
