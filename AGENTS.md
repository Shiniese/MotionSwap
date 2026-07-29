# MotionSwap

> 基于 Go + Fyne 的 Android 动态照片（实况照片）批量提取与合并桌面工具。
> 目的是兼容苹果设备（苹果以同名 JPG + 视频文件存储动图）。

---

## 项目状态

36 个测试全部通过（4 个包），核心逻辑完成，UI 代码已编写完毕。

---

### 支持品牌

| 品牌   | 检测方式                                     | 提取 | 合并 |
|--------|----------------------------------------------|:----:|:----:|
| Xiaomi | XMP: `GCamera:MicroVideoOffset`              | ✓    | ✓    |
| Android (Pixel/Samsung) | `GContainer:ItemLength` / `GCamera:MotionPhoto` | ✓ | ✓ |
| Huawei | ftyp box + EXIF Make=HUAWEI                | ✓    | ✓    |
| Unknown | ftyp box 兜底                              | ✓    | ✓    |

---

## 目录结构

```
MotionSwap/
├── main.go                  # 入口：初始化 Fyne 窗口
├── go.mod / go.sum          # Go 模块 + Fyne v2.8.0
├── AGENTS.md                # 本文件
├── ui/                      # UI 层（Fyne）
│   ├── app.go               # 主窗口 + AppTabs 标签页
│   ├── extract_tab.go       # 提取标签页
│   ├── merge_tab.go         # 合并标签页
│   └── storage.go           # Fyne storage API 辅助函数（readURI/writeURI）
├── core/
│   ├── extractor/           # 提取模块
│   │   ├── extractor.go             # 类型定义 + 接口
│   │   ├── processor_factory.go     # 处理器工厂（自动检测品牌）
│   │   ├── xiaomi_processor.go      # 小米处理器
│   │   ├── android_processor.go     # Android 处理器（3种子格式）
│   │   ├── huawei_processor.go      # 华为处理器（ftyp + EXIF）
│   │   ├── unknown_processor.go     # 未知品牌兜底处理
│   │   └── utils.go                 # 工具函数
│   └── merger/              # 合并模块
│       ├── merger.go         # 合并器入口（Merge / MergeFiles）
│       └── xiaomi_merger.go  # 三家合并器（Xiaomi/Android/Huawei）
├── pkg/
│   ├── xmp/                 # XMP 工具库
│   │   ├── parser.go        # 从 JPEG APP1 提取 + 解析 XMP XML
│   │   └── builder.go       # 构建 XMP XML + 注入 JPEG
│   └── mp4/                 # MP4 工具库
│       └── mp4.go           # ftyp box 检测 + IsMP4 判断
└── 
```

---

## 关键实现细节

### 提取流程

1. 读取 JPEG 二进制数据
2. 搜索 APP1 (0xFFE1) 标记，提取 XMP XML
3. 解析 XMP 为 `map[string]string`
4. 依次尝试各品牌处理器 CanProcess（Xiaomi → Android → Huawei）
5. 无 XMP 匹配时通过 ftyp box 检测（Huawei → Unknown 兜底）
6. 对应处理器拆分 imageData + videoData
7. 计算 stillImageTime（0-255）

### 合并流程

1. 读取 JPEG + MP4 二进制数据
2. 根据目标品牌生成对应 XMP 元数据
3. 注入 XMP 到 JPEG APP1 标记
4. 在 JPEG 末尾追加 MP4 数据
5. 写入输出文件

### XMP 字段对照

| 品牌 | XMP 字段 |
|------|----------|
| Xiaomi | `GCamera:MicroVideo=1`, `GCamera:MicroVideoOffset`, `GCamera:MicroVideoPresentationTimestampUs` |
| Android | `GCamera:MotionPhoto=1`, `GContainer:ItemLength`, `GCamera:MotionPhotoPresentationTimestampUs` |
| Huawei | 无标准 XMP，靠 ftyp box 检测 |

### 品牌自动检测优先级（提取）

1. XMP 中有 `GCamera:MicroVideoOffset` → Xiaomi
2. XMP 中有 `GContainer:ItemLength` 或 `GCamera:MotionPhoto` → Android
3. XMP 中有 `Make=HUAWEI` + ftyp 检测 → Huawei
4. 仅 ftyp 检测成功 → Unknown

---

## 开发命令

```bash
# 运行全部测试
go test ./...

# 运行单包测试
go test -v ./pkg/xmp/
go test -v ./pkg/mp4/
go test -v ./core/extractor/
go test -v ./core/merger/

# 构建（首次需 CGo + X11 dev libs，耗时较长）
go build -o motionswap .

# 运行
./motionswap

# vet 检查
go vet ./pkg/... ./core/... ./ui/
```

---

## 单元测试（36 passed）

| 包 | 测试数 | 说明 |
|----|--------|------|
| `pkg/xmp/parser_test.go` | 10 | XMP 提取与解析（各品牌格式、边界情况） |
| `pkg/xmp/builder_test.go` | 5 | XMP 构建与注入（各品牌圆回测试） |
| `pkg/mp4/mp4_test.go` | 6 | ftyp 检测（正常、截断、JPEG 前缀等） |
| `core/extractor/extractor_test.go` | 5 | 品牌自动检测（Xiaomi/Android/Huawei/Unknown/无匹配） |
| `core/merger/merger_test.go` | 10 | 合并（三家品牌、文件合并、无效输入、圆回） |

---

## 待办 / 已知问题

- [ ] **视频元数据读取**：`pkg/mp4` 尚未实现读取视频时长和帧率（需解析 moov/mvhd box），目前合并时使用固定 presentationTimestamp（500000μs）
- [ ] **stillImageTime 计算**：`core/extractor` 尚未实现完整的 frame rate 计算逻辑（需读取视频元数据）
- [ ] **Fyne 首次构建**：CGo 编译 GL/GLFW 耗时较长，需安装 libgl1-mesa-dev、xorg-dev 等依赖
- [ ] **冲突对话框**：当前使用简单的 `dialog.ShowInformation` + 默认重命名策略，非交互式选择
- [ ] **格式扩展**：暂未支持 HEIC 格式（iOS Live Photos 常用），后续可根据需要添加
- [ ] **异步 UI 同步**：goroutine 中直接操作 UI 控件，在多平台下可能需要 `fyne.Do()` 封装
