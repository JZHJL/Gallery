# MODIFICATIONS

本仓库是 **Fossify Gallery** 的衍生版本，遵循 GNU General Public License v3.0。

- 上游项目：https://github.com/FossifyOrg/Gallery
- 上游许可证：GPL-3.0（见 `LICENSE`，未作任何修改）
- 本仓库：https://github.com/JZHJL/Gallery

## 本衍生版新增/修改的内容

在 AI 语义搜索相关的提交中（`feat(ai):` 开头的提交），新增与修改了以下内容：

### 新增文件

- `app/src/main/kotlin/org/fossify/gallery/databases/AiDatabase.kt` — 独立的 Room 数据库 `ai_index.db`
- `app/src/main/kotlin/org/fossify/gallery/helpers/AiPrefs.kt` — AI 功能配置（API Key、模型、配色、悬浮球参数）
- `app/src/main/kotlin/org/fossify/gallery/helpers/AiMath.kt` — 向量与余弦相似度计算
- `app/src/main/kotlin/org/fossify/gallery/helpers/AiImagePrep.kt` — 图片解码与压缩
- `app/src/main/kotlin/org/fossify/gallery/helpers/AiIndexer.kt` — 批量索引任务
- `app/src/main/kotlin/org/fossify/gallery/helpers/AiIndexState.kt` — 索引进度状态
- `app/src/main/kotlin/org/fossify/gallery/helpers/BailianClient.kt` — 阿里云百炼 API 客户端
- `app/src/main/kotlin/org/fossify/gallery/helpers/AiSearchWidgetProvider.kt` — 桌面搜索小部件
- `app/src/main/kotlin/org/fossify/gallery/activities/AiSearchActivity.kt` — AI 搜索界面
- `app/src/main/kotlin/org/fossify/gallery/adapters/AiResultAdapter.kt` — 搜索结果适配器
- `app/src/main/kotlin/org/fossify/gallery/services/FloatingBallService.kt` — 悬浮球服务
- `app/src/main/res/layout/widget_ai_search.xml`
- `app/src/main/res/drawable/widget_ai_background.xml`
- `app/src/main/res/xml/ai_widget_info.xml`
- `.github/workflows/build-apk.yml`

### 修改的上游文件

- `app/src/main/AndroidManifest.xml` — 增加 INTERNET / SYSTEM_ALERT_WINDOW / FOREGROUND_SERVICE 权限，注册 AI 搜索 Activity、悬浮球 Service、桌面小部件 Receiver
- `app/src/main/res/menu/menu_main.xml` — 增加「AI 搜索」菜单项
- `app/src/main/kotlin/org/fossify/gallery/activities/MainActivity.kt` — 增加菜单点击分支与启动方法
- `app/src/main/kotlin/org/fossify/gallery/activities/SearchActivity.kt` — 原有文件名搜索额外合并 AI 索引的关键词匹配结果

除上述内容外，其余代码与资源均来自上游项目。

## 构建

APK 由 GitHub Actions（`.github/workflows/build-apk.yml`）从本仓库源码直接构建，
因此每个 Release 中的二进制都有本仓库当前源码与之对应。

## 授权

本衍生版整体继续以 **GPL-3.0** 授权。原始版权归 FossifyOrg 及其贡献者所有。
