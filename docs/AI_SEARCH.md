# AI 语义搜索 — 设计文档

本文件描述本仓库相对上游 Fossify Gallery 新增的 AI 搜索子系统。
面向接手二次开发的 AI/人类，尽量给出**事实数据**而不是概览。

## 1. 它解决什么问题

相册里 3000+ 张照片，文件名是 `IMG_20261123_093922.jpg` 这种，按文件名搜等于没有搜索。
目标：**输入一句中文，找到对应的照片。**

例：
- 「消防演练」→ 找到户外消防演习的照片
- 「第23题」→ 找到数学试卷上写着第 23 题的那张
- 「婚礼帐篷」→ 找到粉色灯光下的婚礼现场

## 2. 整体流程

```
建索引（一次性，可中断续跑）
   MediaStore 查询图片
        ↓  content:// URI
   AiImagePrep：解码 → 长边压到 768 → JPEG q85（约 97KB）
        ↓  base64
   BailianClient.describeImage()
        ↓  qwen3-vl-plus，prompt 强制逐字转写画面文字
   中文描述（约 100~150 字）
        ↓
   BailianClient.embedText()
        ↓  text-embedding-v4，dimensions=1024
   FloatArray(1024)
        ↓  AiMath.toBytes()
   写入 Room：ai_index 表

搜索
   用户输入 → embedText() → 查询向量
        ↓
   AiMath.rank()：对库里每条记录算
        score = max(余弦相似度, 关键词命中分)
        阈值 0.25，按分数降序
        ↓
   AiResultAdapter 渲染（网格/列表，带相似度角标）
```

**关键设计：一条描述同时承担语义与 OCR 两个职责。** prompt 里明确要求
「如果画面中有文字、标题、公式、板书、路牌、标签，请逐字转写出来」，
所以对着教材/试卷/票据照片搜索时，命中靠的是描述里的文字而不是语义近似。

## 3. 数据模型

```kotlin
@Entity(tableName = "ai_index")
data class AiIndex(
    @PrimaryKey var uri: String = "",      // content://media/external/images/media/12345
    var path: String = "",                 // /sdcard/DCIM/Camera/IMG_xxx.jpg（用于打开 ViewPager 与关键词匹配）
    var description: String = "",          // 模型生成的中文描述
    var ocrText: String = "",              // 目前与 description 相同（预留独立 OCR）
    var vector: ByteArray = ByteArray(0),  // FloatArray(1024) 小端序列化
    var dim: Int = 0,
    var model: String = "",
    var sizeBytes: Long = 0L,
    var updatedAt: Long = 0L
)
```

- 独立数据库文件 `ai_index.db`，与上游 `gallery.db` 完全隔离（**这是有意为之，方便跟上游合并**）
- 主键用 content URI 而不是文件路径：避免依赖「所有文件访问权限」，且缩略图可直接交给 Picasso
- 向量用 `ByteBuffer` 手工序列化，不引任何数学库
- 3300 张 ≈ 13.5MB 向量数据；暴力算余弦足够快（单次搜索 < 400ms）

## 4. 外部 API

基址：`https://dashscope.aliyuncs.com/compatible-mode/v1`（阿里云百炼，OpenAI 兼容）

| 用途 | 端点 | 模型 | 备注 |
|---|---|---|---|
| 生成描述 | `/chat/completions` | `qwen3-vl-plus` | 图片以 `data:image/jpeg;base64,...` 放在 `image_url` |
| 生成向量 | `/embeddings` | `text-embedding-v4` | `dimensions=1024`，`encoding_format=float` |

**模型可用性坑（该账号实测）**：
- ❌ `qwen-vl-plus`、`qwen3-vl-flash` → 404 Model not exist
- ✅ `qwen3-vl-plus`、`qwen-vl-ocr`、`qwen3-vl-ocr-2025-11-20`
- 向量：`text-embedding-v4`（0.5 元/百万 token）、`qwen3.7-text-embedding`、`qwen3.7-text-embedding-flash`

## 5. 实测成本与性能（20 张样本，真实 usage 字段）

| 指标 | 平均每张 |
|---|---|
| 视觉输入 | 823 token（其中图片 778 + 提示词 45） |
| 视觉输出 | 103 token（≈144 汉字） |
| 向量输入 | 103 token |
| **合计** | **1029 token ≈ ¥0.0019** |
| 耗时（串行） | 4.31s（描述 3.62s + 向量 0.54s + 压缩 0.1s） |
| 耗时（4 线程并发） | 约 1.1s/张 |

单价：`qwen3-vl-plus` 输入 1 元/百万、输出 10 元/百万；`text-embedding-v4` 0.5 元/百万。
**输出比输入贵 10 倍，所以压缩描述长度是主要省钱手段**（长边 1024→768 省 40% 图片 token）。

## 6. 索引任务

`AiIndexer.start(context, prefs, limit)`：

```
Thread {
    targets = MediaStore 查询（DATE_ADDED DESC，取 limit 条）
    固定线程池 4 路并发
    每个任务：
        已存在（按 uri 查库）→ 跳过
        解码压缩 → 描述 → 向量 → upsert
        成功 successCount++；异常 failCount++ 并继续
    CountDownLatch 等待全部完成
}
```

- **进度不落盘**，靠 `AiIndexState` 单例的 `@Volatile` 字段，UI 每 700ms 轮询
- **断点续跑**：靠「按 uri 查库」天然实现，中断后重跑不会重复计费
- 失败不中断整批，错误信息留在 `AiIndexState.lastError`

## 7. UI 结构

`AiSearchActivity`（全部用代码构建 UI，没有 layout 文件）：

```
[搜索框] [搜索]
[最近：消防演练 | 数学试卷]          ← 搜索历史 chip
[不限时间 | 近一年 | 近一月]          ← 时间筛选 chip
┌ 状态卡 ─────────────────────┐
│ 已索引 1284 / 媒体库 3303 张 · 估算 ¥2.440 │
│ 进度 186/500  成功 180  失败 6            │
│ ████████░░░░░░ 37%                       │
└──────────────────────────────┘
[建立索引][停止][设置][悬浮球]
[列表模式 / 网格模式]
RecyclerView：分组标题 + 3 列网格（或 1 列列表显示完整描述）
底部：[已选 N 张] [分享] [取消]      ← 长按多选，仅分享，无删除
```

设置面板：配色（7 色 + 跟随主题）、悬浮球大小/透明度、小部件文字、API Key（多行）、视觉模型、向量模型。

`CustomizationActivity`（上游）里另有全局主题设置。

## 8. 与原相册的关系

- **不接管**原有浏览功能：主界面、相册分组、查看器全部原样保留
- `SearchActivity`（原有按文件名搜索）里加了一个分支：查询长度 ≥ 2 时，
  额外用 `LIKE` 在 `ai_index.description` 上匹配，命中的 path 合并进原有结果网格
  → **纯本地 SQL、不花钱、断网可用**

## 9. Known limitations

1. 索引跑在裸 `Thread`，退到后台/锁屏可能被系统杀掉 → 计划改前台服务
2. 不能按文件夹筛选，会把微信垃圾图也索引进来 → 计划加白名单
3. 新照片不会自动补索引，要手动点
4. `ocrText` 字段目前冗余（等于 description），预留将来接独立 OCR 模型
5. 删除照片功能**故意没做**（误删风险），只做分享
6. 搜索结果依赖描述质量，对极度相似的照片（连拍）区分度有限

## 10. Roadmap（尚未实现）

- [ ] 索引改前台服务 + 通知进度条
- [ ] 只索引指定文件夹（prefs 白名单）
- [ ] 新照片自动补索引
- [ ] 导出/导入索引库（JSON + base64 向量，约 18MB）
- [ ] 以图搜图（拿一张照片的描述当查询）
- [ ] 多 Key 轮换已实现，可考虑加「余额不足自动禁用」

## 11. 二次开发速查

| 想做的事 | 改哪里 |
|---|---|
| 换视觉模型 | `AiPrefs.visionModel` 默认值，或 UI 设置面板 |
| 改 prompt | `BailianClient.VISION_PROMPT` |
| 改图片尺寸/质量 | `AiImagePrep.loadJpeg(maxSide, quality)` 与 `AiIndexer.MAX_SIDE` |
| 改相似度阈值 | `AiMath.rank(threshold)`，默认 0.25 |
| 改分组边界 | `AiSearchActivity.buildRows()` 里的 0.9 / 0.7 |
| 换向量维度 | 需同时改 `AiDatabase.version`（迁移）、`BailianClient.embedText` 的 dimensions、`AiMath.toFloats` |
| 换配色 | `AiPrefs.accentColor` + `AiSearchActivity.showSettingsDialog()` 的 presets |
| 改小部件外观 | `res/layout/widget_ai_search.xml`（注意 RemoteViews 限制） |
