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

---

# 本衍生版新增：AI 语义搜索模块（二次开发必读）

> 以下内容只描述本仓库相对上游的增量。上游原有说明见上文。
> 修改清单见 `MODIFICATIONS.md`；许可证 GPL-3.0，`LICENSE` 未修改。

## A. 定位

在 Fossify Gallery 上增加一套**本地索引 + 云端模型**的中文语义搜图能力：
照片先在本地建索引（模型生成中文描述 + 文本向量，存 Room），搜索时把 query 编码成向量做余弦检索。
另外提供桌面小组件与悬浮球作为入口。

## B. 构建（与上游不同的部分）

| 项 | 值 |
|---|---|
| 构建任务 | `:app:assembleFossRelease`（flavor 只有 `foss` / `gplay`） |
| JDK | 21 |
| Gradle / AGP / Kotlin | 9.5.1 / 9.2.1 / 2.3.10 |
| compileSdk / minSdk | 36 / 26 |
| CI | `.github/workflows/build-apk.yml`（`workflow_dispatch`） |
| 签名 | 通过环境变量注入：`SIGNING_KEY_BASE64`（base64 PKCS12，解码到 `app/release.keystore`）、`SIGNING_STORE_FILE`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD` |

CI 会执行 `apksigner verify --print-certs` 并在日志里打印签名指纹，然后用 `softprops/action-gh-release` 发布 tag `build-<run_number>`。

## C. 文件地图

`app/src/main/kotlin/org/fossify/gallery/`

| 文件 | 职责 | 关键符号 |
|---|---|---|
| `databases/AiDatabase.kt` | 独立 Room 库 `ai_index.db` | `AiIndex`、`AiIndexDao`、`AiDatabase.getInstance(ctx)` |
| `helpers/AiPrefs.kt` | 全部 AI 配置（SharedPreferences `ai_search`） | `apiKeys`、`visionModel`、`embedModel`、`accentColor`、`ballSize`、`ballAlpha`、`ballX/Y`、`widgetText`、`searchHistory` |
| `helpers/BailianClient.kt` | 百炼 OpenAI 兼容客户端 | `describeImage()`、`embedText()`；3 次退避重试，429 自动换 key |
| `helpers/AiImagePrep.kt` | 解码 + 缩放 + JPEG | `loadJpeg(ctx, uri, maxSide, quality)` |
| `helpers/AiMath.kt` | 向量与相似度 | `toBytes`/`toFloats`/`cosine`/`keywordScore`/`rank` |
| `helpers/AiIndexer.kt` | 批量索引（4 路并发） | `start(ctx, prefs, limit)`、`stop()` |
| `helpers/AiIndexState.kt` | 进度单例 | `running/indexed/total/successCount/failCount/lastError` |
| `helpers/AiSearchWidgetProvider.kt` | 桌面小组件 | 点击 → `AiSearchActivity` |
| `activities/AiSearchActivity.kt` | 搜索界面（**纯代码构建 UI，无 layout XML**） | 搜索/历史/时间筛选/分组/列表模式/多选分享/设置 |
| `adapters/AiResultAdapter.kt` | 结果适配器（3 种 viewType） | `Row.Header`、`Row.Item`、`listMode`、`selectionMode` |
| `services/FloatingBallService.kt` | 悬浮球前台服务 | 拖动 / 点击搜索 / 长按菜单 |

资源：`res/layout/widget_ai_search.xml`、`res/drawable/widget_ai_background.xml`、`res/xml/ai_widget_info.xml`

## D. 数据流

索引：
```
MediaStore.Images(DATE_ADDED DESC, limit)
 → 跳过 ai_index 已存在的 uri
 → loadJpeg(长边 768, q85)
 → describeImage()  qwen3-vl-plus（prompt 要求逐字转写画面文字）
 → embedText()      text-embedding-v4, 1024 维
 → AiIndexDao.upsert()
```

搜索：
```
query → embedText() → rank(cosine 与关键词取 max，阈值 0.25) → 分档(Row.Header) → 3 列网格 / 单列列表
```
降级：embedding 失败（断网）时用空向量，走纯关键词。

轻量链路：上游 `SearchActivity.textChanged()` 里对 `ai_index.description/ocrText` 做 SQL `LIKE`，
结果按 `path` 合并进原有网格。不调 API、离线可用。

## E. 表结构 `ai_index`

`uri`(PK, content://) / `path`(TEXT) / `description`(TEXT) / `ocrText`(TEXT) /
`vector`(BLOB, 1024×float32 小端) / `dim`(INT) / `model`(TEXT) / `sizeBytes`(INT) / `updatedAt`(INT)

版本 1，`fallbackToDestructiveMigration`。**改表必须升 version 并写 MIGRATION，否则用户索引全丢。**

## F. 外部 API

- Base：`https://dashscope.aliyuncs.com/compatible-mode/v1`
- 视觉：`qwen3-vl-plus`（该账号可用 VL 仅此一个；`qwen-vl-plus`/`qwen3-vl-flash` 404）
- 向量：`text-embedding-v4`，`dimensions=1024`
- prompt 常量：`BailianClient.VISION_PROMPT`
- 单价：输入 1 元/百万、输出 10 元/百万、向量 0.5 元/百万
- 实测：单张 ≈ 1029 token ≈ **0.0019 元**（输入 823 / 输出 103 / 向量 103）

## G. 与上游的集成点（只有 4 处，改动请保持最小）

1. `AndroidManifest.xml`：`INTERNET`、`SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`；注册 Activity / Service(`specialUse`) / widget Receiver
2. `res/menu/menu_main.xml`：`@+id/ai_search`
3. `activities/MainActivity.kt`：菜单 `when` 分支 + `launchAiSearchActivity()`
4. `activities/SearchActivity.kt`：`textChanged()` 内合并 AI 关键词结果

另：`res/values/strings.xml` 覆盖了 commons 的 `copyright` 字符串（关于页 GPL 归属声明）。

## H. 已知坑（实测）

| 坑 | 症状 | 解法 |
|---|---|---|
| commons 扩展 | `Unresolved reference 'toast'` | `import org.fossify.commons.extensions.*` |
| 跨包引用 | `services` 用 `helpers` 类报未解析 | 显式 import |
| Room 主线程 | 主线程查库抛异常 | DAO 调用放后台线程 |
| 主题色 | 代码 new 的控件白底白字 | `getProperTextColor()` / `getProperBackgroundColor()` |
| RemoteViews | 布局里 `android:tint` inflate 失败 | 只用基础属性，颜色用 `setInt(id,"setBackgroundColor",c)` |
| Service 弹窗 | `AlertDialog` 不显示 | `window.setType(TYPE_APPLICATION_OVERLAY)` |
| CI sdkmanager | `command not found` | `ls $ANDROID_HOME/cmdline-tools/*/bin/sdkmanager` |
| 签名 | 每次构建指纹不同 | 固定 keystore + Secrets |

## I. 待办（下一批）

- `IndexService`：前台服务跑索引，锁屏/后台继续
- 只索引指定文件夹（白名单前缀）
- 新照片自动补索引
- 索引库导出/导入（SAF，JSON + base64 向量，3300 张 ≈ 18MB）
- 以图搜图（复用"描述→向量"）

## J. 验收

装 APK → `⋮` → AI 搜索 → 填 Key → 建立索引 20 张 → 搜「消防演练」→ 看角标；
悬浮球点击/长按；桌面小组件；关于页 GPL 文案。
