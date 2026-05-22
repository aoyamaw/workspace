# 项目文件夹说明

这份文档用来帮助你先从整体上理解项目结构。你不需要一开始看懂所有文件，先知道每个文件夹负责什么功能，再逐步看代码。

## 总体结构

```text
workspace
├── backend      后端项目，负责接口、数据库、天气数据、登录、收藏、通知、AI 助手
├── frontend     前端项目，负责网页界面、用户操作、调用后端接口
├── openspec     项目需求和设计说明，偏开发规划，不是运行必须
├── .codex       AI 辅助开发配置，项目运行不依赖它
├── .env         本地运行配置，例如数据库、端口、API Key
└── README.md    项目总体说明
```

## backend

`backend` 是后端项目，使用 Spring Boot。前端点击按钮后，大多数请求都会发送到后端，再由后端处理业务逻辑。

主要作用：

- 提供 `/api/...` 接口给前端调用
- 查询第三方天气接口
- 保存收藏城市
- 管理匿名用户和注册用户
- 保存 AI 对话记录
- 生成和发送天气通知
- 连接 PostgreSQL 数据库

重要目录：

```text
backend/src/main/java/com/example/weatherapp
```

后端 Java 代码都在这里。

```text
backend/src/main/resources
```

后端配置文件和数据库建表脚本在这里。

```text
backend/src/test
```

后端测试代码在这里，用来验证接口和业务逻辑是否正常。

```text
backend/gradle
backend/gradlew
backend/gradlew.bat
```

Gradle 构建工具相关文件，用来启动、测试、打包后端。

```text
backend/build
backend/.gradle
```

后端编译和缓存生成的目录。你学习项目时一般不用看，删掉后重新构建也会生成。

## backend/src/main/java/com/example/weatherapp

这是后端最核心的代码目录。

```text
api
```

控制器层，也就是后端接口入口。前端请求 `/api/weather/search`、`/api/favorites`、`/api/auth/login` 这些地址时，先进入这里。

你可以理解成：

```text
前端 fetch 请求
-> api 里的 Controller
-> 调用对应 Service
-> 返回结果给前端
```

```text
weather
```

天气查询功能。负责城市匹配、调用天气接口、整理天气数据、写入天气缓存。

重点文件：

- `WeatherService.java`
- `CitySuggestionIndex.java`
- `WeatherResult.java`
- `WeatherSearchResponse.java`

```text
favorites
```

收藏城市功能。负责保存、查询、删除用户收藏的城市。

重点文件：

- `FavoriteCityService.java`
- `FavoriteCityRequest.java`
- `FavoriteCityResponse.java`

```text
identity
```

用户身份功能。负责匿名用户、注册、登录、退出登录、登录 token。

重点文件：

- `IdentityService.java`
- `AuthRequest.java`
- `AuthResponse.java`
- `SessionResponse.java`

```text
assistant
```

AI 天气助手功能。负责接收用户问题，结合当前天气数据生成回答，并保存对话记录。

重点文件：

- `AssistantService.java`
- `AssistantRequest.java`
- `AssistantResponse.java`

```text
notifications
```

天气通知功能。负责通知订阅、通知记录、应用内实时通知、浏览器后台 Web Push、定时扫描。

这个模块相对复杂，建议最后再学。

重点文件：

- `NotificationSubscriptionService.java`
- `NotificationEventService.java`
- `InAppNotificationBroker.java`
- `WebPushService.java`
- `NotificationScheduler.java`

```text
WeatherAppApplication.java
```

后端启动入口。运行后端时，Spring Boot 从这里开始启动。

## backend/src/main/resources

```text
application.properties
```

后端配置文件。这里定义端口、数据库连接、天气 API、AI API、Web Push、通知定时任务等配置。

```text
db/migration
```

数据库迁移脚本目录。后端启动时，Flyway 会读取这里的 SQL 文件自动创建或升级数据库表。

主要文件：

- `V1__init_weather_app.sql`：创建基础表，例如用户、收藏、天气缓存、AI 对话、通知订阅
- `V2__notification_events.sql`：创建通知记录表
- `V3__registered_user_auth.sql`：创建注册用户和登录会话表

注意：这些 `V*.sql` 文件一旦执行过，不建议随便修改，因为 Flyway 会校验文件内容。

## frontend

`frontend` 是前端项目，使用 Vue 3 + Vite。你在浏览器里看到的页面主要由这里控制。

主要作用：

- 显示天气页面
- 搜索城市
- 展示天气预报
- 登录注册表单
- 收藏城市列表
- AI 天气助手聊天框
- 通知设置和通知记录
- 调用后端 `/api/...` 接口

重要目录：

```text
frontend/src
```

前端源码目录。你最应该优先学习这里。

```text
frontend/public
```

静态资源目录。浏览器可以直接访问这里的文件，例如 Service Worker。

```text
frontend/tests
```

前端自动化测试目录。

```text
frontend/node_modules
```

前端依赖包目录。这个目录非常大，是 `npm install` 自动生成的，学习项目时不用看。

```text
frontend/dist
```

前端打包后的结果。执行构建命令后生成，学习源码时不用看。

```text
frontend/test-results
```

前端测试结果目录。学习源码时不用看。

```text
frontend/.vscode
```

前端相关的 VS Code 推荐配置，比如推荐 Vue 插件。

## frontend/src

```text
App.vue
```

前端最重要的文件。页面结构、状态变量、按钮事件、请求后端接口，大部分都在这里。

你应该优先看这个文件。

重点函数：

- `onMounted`：页面第一次打开时执行初始化
- `searchWeather`：搜索天气
- `suggestCities`：搜索框城市联想
- `addCurrentFavorite`：添加收藏城市
- `loadFavorites`：读取收藏城市
- `submitAuth`：登录或注册
- `askAssistant`：向 AI 天气助手提问
- `toggleNotifications`：开启或关闭通知

```text
main.ts
```

前端入口文件。作用是把 `App.vue` 挂载到网页上。

```text
style.css
```

页面样式文件。控制颜色、布局、按钮、卡片、响应式界面等。

```text
assets
```

前端资源目录，例如图片、图标等。

```text
components
```

前端组件目录。当前项目主要逻辑集中在 `App.vue`，这个目录可能暂时没有太多内容。

## frontend/public

```text
sw.js
```

Service Worker 文件。浏览器后台通知 Web Push 会用到它。

如果你暂时只学习天气查询、收藏、登录、AI 助手，可以先不看这个文件。

## openspec

`openspec` 是需求和设计说明目录，主要用于开发规划，不是项目运行必须。

```text
openspec/specs
```

功能规格说明。例如天气查询、收藏通知、AI 天气助手、页面体验等。

```text
openspec/changes
```

开发变更记录。记录某次功能开发的计划、任务和归档内容。

如果你写论文或答辩，可以从这里提取“系统需求分析”和“功能设计”的思路。

## .codex

`.codex` 是 AI 辅助开发配置目录。它不是项目运行必须内容。

主要作用：

- 给 AI 工具提供一些开发流程说明
- 帮助生成或应用 OpenSpec 相关变更

你学习项目代码时可以不用看。

## .env 和 .env.example

```text
.env
```

本地真实运行配置。可能包含数据库密码、API Key 等敏感信息，不建议提交或随便发给别人。

```text
.env.example
```

配置模板。告诉别人这个项目需要哪些环境变量，但一般不放真实密钥。

## 推荐学习顺序

如果你是为了毕业项目理解代码，建议按这个顺序：

```text
1. frontend/src/App.vue
2. frontend/src/main.ts
3. backend/src/main/java/com/example/weatherapp/api
4. backend/src/main/java/com/example/weatherapp/weather
5. backend/src/main/java/com/example/weatherapp/favorites
6. backend/src/main/java/com/example/weatherapp/identity
7. backend/src/main/java/com/example/weatherapp/assistant
8. backend/src/main/resources/db/migration
9. backend/src/main/java/com/example/weatherapp/notifications
```

最开始不要纠结 `node_modules`、`dist`、`build`、`.gradle` 这些目录，它们大多是工具自动生成的。

## 一句话理解这个项目

这个项目是一个前后端分离的智能天气系统：

```text
frontend 负责页面和用户操作
backend 负责接口、业务逻辑和数据库
PostgreSQL 负责保存用户、收藏、缓存、AI 对话和通知记录
第三方天气接口负责提供实时天气和预报数据
AI 接口负责根据天气上下文生成建议
```
