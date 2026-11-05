# Meowpuut

独立的 macOS 注音输入法开发预览。大千键盘，繁简输出，原生候选窗。

## 开发运行

当前构建依赖本机 Homebrew librime 1.17.0，目标为本机 ARM64 / macOS 27；不是可分发的独立安装包，尚未验证旧版 macOS 或 Intel。需要 Xcode Command Line Tools。

```bash
brew install librime
bash scripts/build.sh
bash scripts/open.sh
```

核心检查（独立临时词库，不修改正式用户词库与偏好）：

```bash
.build/Meowpuut.app/Contents/MacOS/Meowpuut --smoke
```

试打使用物理大千键位。例如 `su3cl3` 是「你好」。空格标一声，↑↓ / Tab / ⇧Tab 选择候选，回车提交整句，⇧数字选择候选，PageUp / PageDown 翻页。图钉固定当前注音的常用候选；再次点击取消，也可以在「候选词」页管理。

「更多」按需检索引擎前 256 个候选；「词组」过滤单字，减少长词查找时的干扰。固定词可从前 256 项跨页召回到首页；超出此范围的词目前不能召回。Escape 先退出展开/筛选，保留输入，再按才取消组合。

`Shift + Space` 切换英文；有组合时先提交当前选词。`⇧回车` 按原始键入内容输出英文，适合在注音模式中误打英文后直接恢复。大写开头词、协议网址和普通邮箱沿用 Rime 的识别机制直接输入；不根据普通英文字母猜测切换，因为它们也用于注音。繁简切换保留注音并刷新候选。输入 `/hello` 调用短语模块。

## 系统输入

```bash
bash scripts/install.sh
```

复制到当前用户 `~/Library/Input Methods` 并注册输入源；不会覆盖已有安装。更新需要先退出 Meowpuut，再运行 `bash scripts/install.sh --update`，旧应用保留在 `.build/Previous-Meowpuut-日期.app`。

在设置窗口的「快捷键」页点「启用 Meowpuut」，再从系统输入菜单选用。按钮只启用自己的输入源，不自动切换当前输入法。该操作以及跨应用输入、焦点变化、光标定位和安全输入框兼容性尚待实际验收。开发预览与安装版不要同时运行，以免争用用户词库。

## 结构

- `Sources/Bridge.mm`：Rime C API 边界；注音、词库、OpenCC 转换。
- `Sources/Engine.swift`：输入会话、候选映射、个人首选、英文切换、短语模块。
- `Sources/Interface.swift`：SwiftUI 设置和候选窗、AppKit 试打区。
- `Sources/main.swift`：InputMethodKit 系统接入、应用入口与核心检查。
- `Sources/SystemInput.swift`：用户主动触发的系统输入源启用。
- `Modules/`：版本化的本地短语模块示例。
- `Vendor/`：保留原始许可的第三方方案、词库和语言模型；参见 `THIRD_PARTY.md`。

模块目前支持短语，不承诺尚未实现的主题或二进制扩展 API。词库排序与上下文来自 Rime / terra_pinyin / essay；Meowpuut 自己实现界面、个人候选偏好、会话交互与模块格式。没有借用 Squirrel 的应用代码。

## 模块

设置中打开模块文件夹，添加 JSON 后点「重新加载」。当前 API v1：

```json
{"id":"my.replies","apiVersion":1,"name":"工作回复","trigger":"/work","phrases":["收到，我稍后回复。","Thanks, I'll get back to you."]}
```

模块不执行脚本。文件限制 1 MiB、100 条短语；无效文件会显示错误。用户数据保存在 `~/Library/Application Support/Meowpuut`，个人首选与输出模式保存在应用偏好中。
