## MODIFIED Requirements

### Requirement: Main weather dashboard
系统 SHALL 提供主天气仪表盘，让用户可以搜索城市、查看天气结果、访问收藏、打开 AI 助手，并查看当前搜索地区的本地推荐卡片。

#### Scenario: 用户打开应用
- **WHEN** 用户打开 Web 应用
- **THEN** 系统 MUST 展示城市搜索入口、天气结果区域、收藏入口、AI 助手入口，以及可在地区选中后展示本地美食和游玩地点卡片的推荐区域

#### Scenario: 用户成功搜索地区
- **WHEN** 用户搜索地区且系统解析出天气结果
- **THEN** 系统 MUST 将当前地区提供给推荐区域，以便为该地区加载本地美食和游玩地点卡片
