# Python 中文报错助手

这是一个为 PyCharm 2026.1 制作的离线插件。Python 程序因异常退出后，它会自动读取运行输出中的 traceback，并直接在当前“运行”控制台中显示：

- 中文错误原因；
- 最可能需要修改的项目文件和代码行；
- 对应的源代码；
- 针对常见 Python 异常的修改建议；
- 可点击跳转的出错位置；
- 跳转后自动将建议修改的表达式标黄，并在编辑器内显示修改提示；
- 修改代码后自动清除黄色定位高亮；
- 只标黄能够可靠确定的错误表达式，绝不使用整行黄色高亮；
- 区分“错误爆发点”和“真正赋值/配置错误”：可向上回溯数量变量的错误赋值，以及 Linear、RNN、卷积和归一化层的维度配置；
- 控制台明确标注“已向上回溯的根因位置”或“错误触发位置（根因可能在前面）”，不把不确定位置冒充根因；
- 对高置信度替换提供“确认后应用这条修改”，修改前显示文件、行号和替换内容，并支持 Ctrl+Z 撤销；
- 每次异常都会结合控制台运行信息，完整扫描当前报错 `.py` 文件；不会遍历整个项目或其他目录；
- 支持保留并分析多行异常详情，可识别 state_dict 的尺寸不匹配、缺失键、意外键、DataParallel 前缀以及常见层参数映射；
- “查看原始报错 / 查看中文解释”一键切换。
- 编辑代码时实时检查 PyTorch 优化器中的 `model.parameters`，用中文提示补上 `()`，并提供一键修复。

报错后默认显示中文解释。点击控制台中的切换链接，可以恢复完整原始 traceback，再点一次即可回到中文解释。

## 隐私

插件不联网、不调用任何 AI 服务、不要求账号或 API Key，也不会上传代码。所有分析均在本机完成。

## 当前支持

安装包针对本机 PyCharm 2026.1.1（内部版本 261）构建，兼容 PyCharm 2026.1.x。

插件能识别标准 Python traceback，并对 SyntaxError、IndentationError、NameError、ModuleNotFoundError、TypeError、AttributeError、ValueError、IndexError、KeyError、FileNotFoundError、PermissionError、ZeroDivisionError 等常见异常给出专门建议。根因规则还覆盖内置函数名被覆盖、None 来源、错误索引、文件路径来源、除数赋零、拆包数量，以及 PyTorch 的层维度、dtype、device、Embedding、损失函数、reshape、NumPy 转换、BatchNorm 和变长批处理等场景。

规则分析可以准确解释常见运行错误，但无法理解所有业务逻辑。如果错误由数据或业务条件导致，应结合变量实际值继续排查。

## 构建

普通使用者无需自行构建，直接安装 `dist` 目录中的 `PythonChineseErrorHelper-2.1.0.zip` 文件即可。开发者可在 PowerShell 中运行：

```powershell
.\build.ps1
```

构建过程使用 PyCharm 自带的 Java 运行时，不需要下载 Gradle 或其他依赖。
