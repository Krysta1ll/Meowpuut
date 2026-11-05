# 第三方来源

不复制已有输入法应用的架构或界面。引擎通过 C API 接入，方案及数据按原许可保留。

| 来源 | 固定修订 | 许可 |
| --- | --- | --- |
| https://github.com/rime/librime | Homebrew 1.17.0_2 | BSD-3-Clause |
| https://github.com/rime/rime-bopomofo | 6085c9a38a4a728047862b33d67eee18aa86f3b9 | LGPL-3.0 |
| https://github.com/rime/rime-terra-pinyin | 723e51bc266cf9464530c1ddedb856aa18e3da34 | LGPL-3.0 |
| https://github.com/rime/rime-prelude | 082425ea0684bca36474415d4a0e8db9b016487e | LGPL-3.0 |
| https://github.com/rime/rime-stroke | 1e8fff9b9494ddec23b0cbc526bcfd8171a6fd48 | LGPL-3.0 |
| https://github.com/rime/rime-essay | 054920de4f54c9e5994276a96a4fc2a35cb51aa3 | LGPL-3.0 |
| https://github.com/BYVoid/OpenCC | Homebrew 1.4.2 | Apache-2.0 |

各 Vendor 目录保留 LICENSE、AUTHORS 与原始文件；Meowpuut 的方案覆盖单独放在 Resources/default.custom.yaml。SF Symbols 通过系统 AppKit API 绘制，不从第三方下载图标。

`Resources/bopomofo.custom.yaml` 的邮箱 recognizer 复用 rime-prelude/default.yaml 原表达式；保留 bopomofo 较窄的网址识别，避免把注音的 `.`（ㄡ）过早当成域名。
