# AGENTS.md — 给 AI 编码助手的上下文说明

> 本文件面向 **AI 编码助手**（Claude Code / Codex / Cursor / Copilot Workspace 等）。
> 目标：让一个新接手的 AI 在 **不读完整仓库** 的前提下，能安全地做二次开发。
> 人类开发者可参考，但优先级次于仓库自身代码。

## 一句话说明这是什么

**Fossify Gallery 的 Android 修改版**，在原有相册之上增加了「AI 语义搜索」：
用阿里云百炼的视觉模型为每张照片生成中文描述，把描述转成向量存进本地 Room 数据库，
搜索时把关键词转成向量做余弦匹配。

- 上游：https://github.com/FossifyOrg/Gallery （GPL-3.0）
- 本仓库的修改清单：`MODIFICATIONS.md`（**改任何东西前先读它**）
- AI 功能的详细设计：`docs/AI_SEARCH.md`

## 技术栈事实（别猜，照这个来）

| 项 | 值 |
|---|---|
| 语言 | Kotlin 2.3.10（**没有 Compose**，用传统 XML View + 代码构建 UI） |
| 构建 | Gradle 9.5.1 + AGP 9.2.1，`compileSdk 36` / `targetSdk 36` / `minSdk 26` |
| JDK | **21**（CI 用 temurin 21） |
| 架构组件 | Room 2.x（KSP 生成）、RecyclerView、ViewBinding、AppCompat |
| 基础库 | `fossify-commons`（BaseSimpleActivity / SimpleActivity / 扩展函数） |
| 图片加载 | Picasso（AI 界面）、Glide（原相册） |
| 网络 | `HttpURLConnection`（**没有 OkHttp/Retrofit 依赖**，别引入） |
| JSON | `org.json`（Android 内置） |

## 目录地图（只需要记住这些）

```
app/src/main/kotlin/org/fossify/gallery/
├── activities/
│   ├── MainActivity.kt        # 主界面，1752 行，尽量不要动
│   ├── SearchActivity.kt      # 原有「按文件名搜索」，已接入 AI 关键词结果
│   ├── AiSearchActivity.kt    # ★ AI 搜索界面（新增，全部代码构建 UI）
│   ├── ViewPagerActivity.kt   # 全屏看图，AI 结果点击后复用它
│   └── SettingsActivity.kt    # 设置（来自 commons）
├── adapters/
│   ├── MediaAdapter.kt        # 原相册网格
│   └── AiResultAdapter.kt     # ★ AI 结果：网格/列表双模式、分组标题、多选
├── databases/
│   ├── GalleryDatabase.kt     # 原相册 Room 库（version=10，别乱改）
│   └── AiDatabase.kt          # ★ AI 索引 Room 库（version=1，独立文件 ai_index.db）
├── helpers/
│   ├── AiPrefs.kt             # ★ 所有 AI 配置（Key/模型/配色/悬浮球参数）
│   ├── BailianClient.kt       # ★ 百炼 API 客户端（描述 + 向量，含重试与 Key 轮换）
│   ├── AiMath.kt              # ★ 向量序列化 + 余弦 + 关键词打分
│   ├── AiImagePrep.kt         # ★ 解码缩放压缩（长边 768）
│   ├── AiIndexer.kt           # ★ 批量索引任务（4 线程并发）
│   ├── AiIndexState.kt        # ★ 索引进度单例（Volatile 字段，UI 轮询）
│   ├── AiSearchWidgetProvider.kt  # ★ 桌面小部件
│   └── MyWidgetProvider.kt    # 原有文件夹小部件
├── services/
│   └── FloatingBallService.kt # ★ 悬浮球（前台服务 + 悬浮窗）
└── ...

app/src/main/res/
├── layout/widget_ai_search.xml        # ★ 小部件布局（RemoteViews，注意限制）
├── drawable/widget_ai_background.xml  # ★ 小部件圆角背景
└── xml/ai_widget_info.xml             # ★ 小部件声明
```

★ = 本次修改新增的文件。**改动应尽量局限在 ★ 文件内。**

## 常用命令

```bash
# 只在 CI 构建（本地没有 Android SDK，别尝试本地编译）
gh workflow run build-apk.yml --repo JZHJL/Gallery

# 产物：GitHub Release build-<run_number> 里的 albumsearch-release.apk
# 构建日志：https://github.com/JZHJL/Gallery/actions
```

CI：`.github/workflows/build-apk.yml`，单 job，`./gradlew :app:assembleFossRelease`。
签名密钥通过 5 个 GitHub Secrets 注入（`SIGNING_KEY_BASE64` 等），**仓库里绝不存密钥**。

## 硬性约束（违反会构建失败或功能回归）

1. **不要引入新依赖**。新增网络/JSON/图片能力优先用 `HttpURLConnection` / `org.json` / Picasso/Glide。
2. **不要改 `GalleryDatabase` 的 version 或实体**。AI 数据放在独立的 `AiDatabase`。
3. **不要用 Compose**。commons 的 About 页是 Compose，但本 App 正文全是 View 体系。
4. **代码构建的控件必须显式上色**：
   ```kotlin
   textView.setTextColor(getProperTextColor())
   root.setBackgroundColor(getProperBackgroundColor())
   ```
   否则会出现「白底白字」（Fossify 主题是动态改窗口背景实现的，属性不会自动继承）。
5. **Room 不允许主线程查询**。所有 `dao.*()` 调用必须在 `Thread` / `ExecutorService` 里。
6. **RemoteViews（小部件）只支持有限属性**。不要用 `android:tint`、`ImageView` 的复杂属性，
   会 inflate 失败。颜色用 `views.setInt(id, "setBackgroundColor", color)` 在运行时设置。
7. **长任务在 Termux/CI 上可能被杀**，索引逻辑不要依赖 Activity 生命周期；改造时考虑前台服务。

## 主题与配色约定

- 应用整体主题：`设置 → 自定义`（commons 提供）
- AI 模块强调色：`AiPrefs.accentColor`（0 = 跟随主色），影响悬浮球、小部件、筛选高亮
- 取色统一走：`getProperTextColor()` / `getProperBackgroundColor()` / `getProperPrimaryColor()`

## 已知限制 / 待办清单

见 `docs/AI_SEARCH.md` 的「Known limitations」与「Roadmap」两节。

## 版权

新增代码随本仓库整体以 GPL-3.0 授权。修改上游文件时**必须保留原有版权头**。
