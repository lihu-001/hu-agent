# hu-agent 开发说明

这个仓库是 hu-agent 的源码：`hu` 命令就是从这里构建出来的。用户要求修改 hu 的界面或行为（状态栏、输入框、启动页、斜杠命令、工具、权限确认等）时，改的就是这里的代码。

## 构建与测试

- 需要 JDK 25。系统 PATH 上可能是旧版本，运行 Maven 前先让 `JAVA_HOME` 指向 JDK 25。
- 全部测试：`./mvnw -o -q test`（Windows PowerShell 里是 `./mvnw.cmd -o -q test`；不需要 API Key，模型接口用本地模拟）。单个测试类加 `-Dtest=GrepToolTest`。
- 改完至少运行相关的测试类；改了公共行为（工具、ChatLoop、提示词、配置默认值）时运行全部测试。
- 正在运行的 hu 不会因为改了源码而变化。改完并通过测试后，提醒用户退出 hu，再在本目录运行 `hu update --local` 构建并安装。不要在 hu 的 shell 工具里自己运行它：它会替换正在使用的安装。

## 代码在哪里

| 位置 | 内容 |
| --- | --- |
| `HuMain` | hu 命令的入口：读配置、组装会话、启动页、终端标题 |
| `prompt/` | 终端界面。`FramedPrompt` 是输入框上方那条分隔线（状态栏：自动审批标记、模型名、上下文剩余与最大值）；`Splash` 是启动页；`LineEditor`、`SlashMenu`、`SlashCompleter` 负责输入行与斜杠命令列表；`TerminalTitle` 是终端标题 |
| `loop/ChatLoop` | 对话循环：请求模型、执行工具、每轮调用上限、终端显示 |
| `harness/` | 显示与运行辅助：`Transcript`（回复与工具调用在终端上的分段样式：`●` 标记、`⎿` 结果行、段间空行）、`ToolTrace`（工具调用摘要的内容）、`TurnInterrupt`（Esc / Ctrl+C 中断）、`EventHub` |
| `tool/`、`tool/file/` | 基础工具：shell、read_file、write_file、edit_file、glob、grep |
| `permission/` | 权限规则（`PermissionPolicy`）、确认提示（`ConfirmPrompt`）、自动审批 |
| `command/` | 斜杠命令（`/model`、`/status`、`/auto` 等） |
| `config/` | `config.properties`、`model.properties` 与默认值 |
| `context/` | 会话开始时附加到系统提示词的项目上下文（环境、git 状态、AGENTS.md） |
| `compact/` | 上下文压缩 |
| `app/` | 教程各阶段（S02–S18）的入口与组装，hu 用的是其中最完整的 `TeamMain` / `ScheduleMain` |

## 写法约定

- 注释、终端文字和提示词都用中文；沿用周围代码的命名、注释密度和写法。
- 项目按教程阶段演进：旧的构造器和入口保持上一阶段的行为，需要新参数时新增构造器或可选字段（`null` 表示保持原样），不要改变旧入口的行为。
- 测试会断言终端输出和工具结果的原文，改了文字要同步更新测试。
- 行为有变化时同步更新 README.md 和 `src/main/resources/hu-agent/*.example.properties`。
