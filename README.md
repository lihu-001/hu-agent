# hu-agent

终端里的 AI 编程助手：在项目目录输入 `hu` 即可开始对话。它会读写文件、执行命令，并按你的确认行事。基于 [java-agent](https://github.com/lihu-001/java-agent) 教程 S18 的完整实现，包括权限确认、Hook、任务清单、子代理、技能、上下文压缩、记忆、任务图、后台任务、定时计划、团队协作、MCP、工作流、`/goal` 目标循环和斜杠命令。

## 安装

macOS / Linux：

```sh
curl -fsSL https://raw.githubusercontent.com/lihu-001/hu-agent/main/install.sh | sh
```

Windows（PowerShell）：

```powershell
irm https://raw.githubusercontent.com/lihu-001/hu-agent/main/install.ps1 | iex
```

不需要预先安装 Java。安装脚本会做这些事：

- 下载发布包并校验 sha256；
- 下载一份锁定版本的 Temurin JRE 25（约 60MB，sha256 写死在脚本里）；
- 把 `hu` 加入 PATH。

hu 只使用这份私有 JRE，和电脑上已装的 JDK 互不影响。

支持平台：

| 系统 | 支持的架构 |
| --- | --- |
| Linux | x64、arm64、Alpine x64 |
| macOS | Intel、Apple 芯片 |
| Windows | x64（ARM64 通过系统仿真运行） |

安装后的目录：

```text
~/.hu-agent/
├── app/                 启动脚本与 hu-agent.jar（每次安装整体替换）
├── runtime/             私有 Java 运行时（锁定版本变化时才重新下载）
├── model.properties     模型接口配置（首次运行时引导生成）
├── config.properties    其他设置（首次运行时按模板生成）
├── hook-trust/          Hook 授权记录
└── mcp-trust/           MCP 授权记录
~/.local/bin/hu          → ~/.hu-agent/app/bin/hu（Windows 把 app\bin 加入用户 PATH）
```

**升级**：运行 `hu update`（等同于重新运行安装命令，也可以直接重跑安装命令）。配置、授权记录和 JRE 都会保留。升级前先退出其他正在运行的 `hu`。

**离线安装应用包**：`hu update --pkg <路径>`，路径是 `hu-agent.zip`（Windows）/ `hu-agent.tar.gz`（macOS / Linux），或包含它的目录。使用随包安装的安装脚本，不联网、不构建。

**安装指定版本**：先设置环境变量 `HU_VERSION=v0.1.0`，再运行安装命令。

**卸载**：
- macOS / Linux：删除 `~/.hu-agent` 和 `~/.local/bin/hu`，再删掉 shell 配置文件里 `# hu-agent PATH` 下面那一行。
- Windows：删除 `%USERPROFILE%\.hu-agent`，再从用户 PATH 中去掉 `...\.hu-agent\app\bin`。

### 安装脚本的环境变量

| 变量 | 作用 |
| --- | --- |
| `HU_VERSION` | 安装指定版本，默认最新发布 |
| `HU_HOME` | 安装目录，默认 `~/.hu-agent` |
| `HU_BIN_DIR` | `hu` 链接所在目录，默认 `~/.local/bin`（仅 macOS / Linux） |
| `HU_DOWNLOAD_BASE` | 应用包下载地址前缀，用于镜像。目录下需要有 `hu-agent.tar.gz`、`hu-agent.zip` 及对应的 `.sha256` |
| `HU_JRE_BASE` | JRE 下载地址前缀，用于镜像。文件名与 Adoptium 官方一致 |
| `HU_NO_MODIFY_PATH=1` | 不修改 shell 配置文件或用户 PATH |
| `HU_PACKAGE` | 安装本地应用包（`hu-agent.zip` / `hu-agent.tar.gz` 或其所在目录），不下载。`hu update --pkg` 和 `--local` 用的就是它 |

国内访问 GitHub 慢的话，可以用 `HU_DOWNLOAD_BASE` 和 `HU_JRE_BASE` 指向自己的镜像。JRE 的 sha256 写死在脚本里，所以换了镜像也照样校验。

## 使用

```sh
cd 你的项目
hu
```

首次运行会引导配置模型，保存到 `~/.hu-agent/model.properties`：

1. 选择接口类型：

   | 序号 | `provider` | 说明 |
   | --- | --- | --- |
   | 1 | `openai` | OpenAI 官方，默认地址 `https://api.openai.com/v1` |
   | 2 | `anthropic` | Anthropic 官方，默认地址 `https://api.anthropic.com` |
   | 3 | `openai-compatible` | OpenAI 格式（Chat Completions）的自定义接口，请求发往 `<地址>/chat/completions` |
   | 4 | `anthropic-compatible` | Anthropic 格式（Messages）的自定义接口，请求发往 `<地址>/v1/messages` |

2. 接口地址：只有自定义接口（3、4）需要填写，官方接口用默认地址（在文件里填 `base-url` 可以改成代理地址）；
3. 模型名称；
4. API Key：输入时不显示。

之后可以直接编辑 `model.properties`，或删掉它再运行 `hu` 重新引导。模型最大上下文 `max-context-tokens`、Anthropic 协议每次回复的输出上限 `max-output-tokens`（官方默认 32000，自定义接口默认 8192）也在这个文件里，见 [model.example.properties](src/main/resources/hu-agent/model.example.properties)。Anthropic 格式自定义接口除 `x-api-key` 外还会发送 `Authorization: Bearer`，两种鉴权方式的服务都能用。

也可以完全用环境变量配置，这时不会出现首次引导：

```sh
export HU_PROVIDER=anthropic                  # 可省略：未设置时按 OpenAI 协议，有 HU_BASE_URL 视为自定义接口
export HU_MODEL=模型名称
export HU_API_KEY=sk-...
export HU_BASE_URL=https://example.com/v1     # 官方接口可省略
```

环境变量优先于配置文件。其他设置（调用上限、超时、压缩阈值、团队模式等）在 `~/.hu-agent/config.properties`，见 [config.example.properties](src/main/resources/hu-agent/config.example.properties)。

从旧版升级：旧的 `config.properties` 里以 `openai.*` 写的模型设置在没有 `model.properties` 时继续生效（按 OpenAI 协议），不会重新引导；想换成 Anthropic 接口，删掉这几行再运行 `hu` 即可。

进入会话后：

- 输入框与 Claude Code 一样固定在终端最下方：状态栏分隔线和 `❯` 输入行始终在最后，回复、工具调用和后台通知都插在它上方；回车后输入框收起，输入以 `❯ 输入` 留在对话里。模型或工具运行中输入框照样显示，上方多一行 `运行中…（Esc 中断，回车排队）`，可以继续键入和编辑；回车把这条输入排进队列（显示在 `已排队：` 一行），本轮结束后依次发送，按 Esc 或 Ctrl+C 中断本轮时排队的输入不发送、放回输入行；
- 输入 `/` 会在输入行下方列出命令，继续输入即时过滤；一次最多显示 10 个，↑↓ 选择时列表跟着滚动，也可以用鼠标滚轮滚动；Tab 填入选中的命令（未选中时为第一个），回车在选中后填入，再回车执行；
- 不以 `/` 开头时 ↑↓ 翻看本次会话的输入历史；
- 粘贴与 Claude Code 一致：多行或超过 800 个字符的文本在输入行里只显示一行占位，如 `[粘贴文本 #1，共 12 行]`，回车时以原文发送，粘贴里的换行不会提交；退格整个删掉占位，↑ 取回的历史同样以原文发送。Windows 控制台不支持括号粘贴时，按连续送达的按键识别粘贴；
- Esc 与 Ctrl+C 与 Claude Code 一致：输入行有内容时连按两次 Esc（800 ms 内）清空，清掉的内容进历史、↑ 可找回；Ctrl+C 清空当前行并提示再按一次退出，800 ms 内再按退出；空行上 Ctrl+D 也退出；
- 模型或工具运行中按 Esc 或 Ctrl+C 中断本轮：正在进行的模型请求停止，Shell 命令连同子进程被终止，本轮已有的对话和工具结果保留在历史里（并告诉模型是用户中断的），回到输入提示符；连按两次 Ctrl+C 中断后退出。确认提示里按下时拒绝这次调用并中断本轮。运行中还没回车的文字留在输入行里。后台任务与队友不受影响；
- 发送图片：终端粘贴截图（终端会把图片存成临时文件、输入框里得到它的路径）或拖入图片文件后回车，输入里的图片绝对路径会作为图片随这条消息发给模型，路径文字也保留，回车后显示 `⎿  已附加图片 文件名（宽×高）`。支持 PNG、JPEG、GIF、WebP（按文件头判断格式），路径含空格时用引号括起；每条最多 20 张，宽或高超过 2000 像素、或文件超过 3.75 MB 时按比例缩小后发送（WebP 无法缩小，过大时不附加并说明原因）。图片留在对话历史里，之后的请求也会带上；模型需要支持图片输入；
- `/help`：查看全部命令；
- `/model`：列出当前模型、常用模型与接口提供的全部模型；输入 `/model ` 加空格后在下方列出这些模型，继续输入按名称过滤，↑↓ 选择后回车填入、再回车切换；`/model <名称>`：切换同一接口下的模型，不清空对话，并写回配置文件作为默认；
- `/status`、`/tools`、`/skills`、`/mcp`：查看状态；
- `/clear`：清空对话；
- `/compact`：立即压缩上下文；
- `/goal <条件>`：设定目标，未达成时自动续跑；
- `/auto`：切换自动审批模式，`/auto on`、`/auto off` 直接指定，见下文；
- `exit`：退出。

`/model` 的模型列表：`model.properties` 里 `models=` 列出的常用模型排在最前（逗号分隔，可选），其后是接口 `<base-url>/models` 返回的全部模型（Anthropic 协议为 `/v1/models`）。接口列表在会话开始时后台查询一次，不影响启动；接口不提供模型列表时只列常用模型。

`/model <名称>` 切换后写回启动时读取模型的文件（`model.properties` 的 `model`；旧版写法写 `config.properties` 的 `openai.model`），只改这一行，下次启动沿用。为了不把拼错的名字留到下次启动，只有常用模型或接口列表里有的模型才写回，其他名称只在本次会话生效；设置了环境变量 `HU_MODEL` 时也只在本次会话生效。接口、密钥与 token 上限（`max-context-tokens`、`max-output-tokens`）不随模型变化。

切换后，共用客户端的子代理、队友、上下文压缩与默认目标判断器从后续请求起使用新模型；已发出的请求和 `goal.evaluator-model` 单独指定的判断器不变。`/status` 与输入栏同步显示当前模型；旧模型的上下文用量清除，等待新请求返回 usage 后更新。命令本身不请求模型、不触发 Hook、不进入对话历史。

自动审批模式：`hu` 启动时默认开启，`hu --no-auto` 启动时不开启、逐次确认（可与 `-d` 同时使用；`--auto` 保留兼容，与 `--no-auto` 同时给出时以后一个为准），会话中用 `/auto` 随时切换。开启后，原本要逐次确认的工具调用（Shell、写文件、后台命令、MCP 工具、写记忆、创建队友等）自动批准，不再停下来等 `y`，每次只显示一行记录，如 `【自动批准】shell：mvn test`。主 Agent、子代理、工作流步骤、队友，以及定时计划或后台完成唤醒的轮次都一样。以下照常拒绝：硬拒绝规则（`sudo`、关机、`rm -rf /`、直接读写磁盘等），工作目录之外的路径，没有权限规则的工具。Hook 与 MCP 配置的信任确认是启动时对项目配置的一次性授权，不受影响。开启时输入栏分隔线最前面显示 `自动审批`，`/status` 也会说明。只在可信的项目里使用。

关闭自动审批时逐次确认，与 Claude Code 一样有三种回答：`y` 只允许这一次，`a` 本次会话不再询问，直接回车或其他回答拒绝。`a` 放行的范围写在问题里：Shell、后台命令与定时计划按完整命令放行，只有一字不差的同一条命令不再询问（如 `【权限确认】shell：mvn test  仅允许本次调用？[y/N，a=本次会话不再询问这条命令]`）；写文件与编辑合为“文件修改”，放行后两者都不再询问；MCP 工具、写记忆、创建队友等按工具放行。之后命中范围的调用只显示一行记录，如 `【本会话允许】shell：mvn test`（写文件、编辑同样带改动预览），定时计划或后台完成唤醒的轮次也照此放行。主 Agent、子代理与队友共用放行范围。放行只在内存里，退出 `hu` 即失效，`/auto` 切换不影响它；硬拒绝规则与工作目录检查照常生效。

`hu --version` 查看版本，`hu --help` 查看用法，`hu -p "任务"` 非交互地执行一个任务（见下文）。

启动时先显示启动页：渐变色像素字 `hu`、版本和一条随机使用提示。颜色只在能解释 ANSI 转义的终端里显示（macOS / Linux 终端，Windows 上的 Windows Terminal、VS Code 终端、ConEmu），传统控制台窗口里不带颜色；设置环境变量 `NO_COLOR` 可关闭颜色。

交互输入框上方状态栏使用普通文本（不加颜色、无需配置）：`── 模型 ── git:分支 ── 上下文剩余 99% · 998.2K / 1M ──`；用量未知时显示 `上下文未知 / 1M`。窄窗口先精简上下文，再从中间省略长模型名和分支名；极窄时依次优先保留自动审批、上下文余量、模型、分支，已知列数时不超过列数减一。GBK 等终端使用 `-`、`.` 等 ASCII 分隔符。状态栏随输入框每次重画重新计算（窗口缩放、`/auto`、`/model` 后立即更新），每次重画都会从配置的工作目录向上查找并直接读取 `.git/HEAD`（支持 `.git` 的 gitdir 文件、worktree、尚无提交的分支和 `detached@短hash`），不启动 Git 进程；非仓库或读取失败时隐藏分支。

默认显示对话、模型的中间说明、权限确认，以及每次工具调用的摘要（与 Claude Code 相同，完整结果只给模型）。回复与每次工具调用各成一段，段与段之间空一行：回复以 `●` 开头，多行时之后的行缩进两格；工具调用第一行是 `● 工具名 参数`，第二行是 `⎿  结果`，如 `● grep 上下文剩余` / `⎿  找到 1 个文件`、`● shell` / `⎿  退出码 0，12 行输出`。终端支持颜色时工具的 `●` 为绿色、失败（工具错误、权限拒绝、被中断等）为红色，结果变暗；终端字符集不能显示 `●`、`⎿`（如 GBK 控制台）时改用 `*` 与 `-`。与 Claude Code 一样内容不贴着终端最左列：后台通知、自动批准记录、提示等顶格的行缩进两格，斜杠命令的输出整体缩进两格；超过终端宽度的行由 hu 自己折行（英文按词、中文按字），续行与正文对齐（`●`、`⎿` 之后），不会折到最左列。输出被重定向时保持原来的 `助手> `、`工具> ` 各一行，`--debug` 时回复仍以 `助手> ` 开头。读文件、搜索这类免确认的工具在摘要里显示路径或模式；需要确认的工具参数已经显示在确认行里，摘要只显示结果，确认行与摘要同属一段。权限确认以【权限确认】开头（终端支持时为黄色），如 `【权限确认】shell：git status  仅允许本次调用？[y/N，a=本次会话不再询问这条命令]`（选项见上文）。与 Claude Code 一样，写文件和编辑显示的是改动本身：确认行写明文件和增删行数（如 `edit_file：src/A.java（+1 −0）`），下面是带行号的 diff，新增行以 `+` 标出（绿色）、删除行以 `-` 标出（红色），前后各带两行上下文，远离改动的未改动行折叠，最多显示 60 行；新建文件显示开头 20 行；多行 Shell 命令逐行原样显示；问题单独一行。自动审批模式下的记录也带同样的预览。其他工具参数只有一个字符串时直接显示它，否则显示一行 JSON，参数很长时问题另起一行。排查问题时用 `hu --debug`（或 `-d`）启动，会额外显示每次模型请求与耗时、内置 Hook 日志、微压缩、启动清单、工具调用（含 ID 与完整参数）和完整工具结果，权限确认恢复为原因、工具与调用 ID、参数、问题各一行。

### 非交互运行（`-p`）

与 Claude Code 的 `-p` 相同，`hu -p` 执行一个任务后退出，用于脚本和评测框架（SWE-bench、Terminal-Bench 等）：

```sh
hu -p "修复 src/A.java 里失败的测试"   # 多个任务参数以空格连接
cat issue.md | hu -p                  # 没有任务参数（或任务为 -）时从标准输入读取
hu -p -d --no-auto -- "-x 选项是做什么的"  # 选项可以放在任意位置，-- 之后都是任务文字
```

- 最终回答写到标准输出，过程（工具调用摘要、中间说明、自动批准记录、提示与错误）写到标准错误，所以 `hu -p "写提交说明" > msg.txt` 只把回答写进文件。`-d` 时标准错误与交互会话的调试输出相同（含完整工具结果，最终回答也在其中），可以当作轨迹日志。输出被重定向时两者都是 UTF-8。
- 退出码：`0` 得到最终回答；`1` 未配置模型、配置错误、请求失败，或这一轮没有最终回答（如达到模型调用上限、模型最后没有文字回答）；`2` 参数错误或没有任务。
- 任务作为一条消息只跑一轮，多行任务不会被拆开；输入里的图片路径同样作为图片发送。斜杠命令可用（如 `/技能名`、`/goal`），只在本地打印的命令（如 `/status`）没有最终回答。
- 需要确认的操作按自动审批处理（默认开启）；`--no-auto` 时没有人回答，一律拒绝。没有信任记录的项目级 Hook 与 MCP server 不会启用，需要时先在交互会话中确认一次。
- 不显示启动页，不运行首次配置引导：没有 `model.properties` 时用环境变量 `HU_MODEL`、`HU_API_KEY` 等配置（见上文），在容器里跑评测时很方便。
- 一轮结束会话就结束：不开团队模式，不启动定时计划，仍在运行的后台命令被取消。

### 工具与项目上下文

基础工具与 Claude Code 对应：`glob` 按文件名查找，`grep` 按内容搜索（Java 正则；只读、免确认；git 仓库里遵守 `.gitignore`；`output_mode` 可选只列文件、显示匹配行与行号、计数），`read_file` 读文件（每行带行号，默认 2000 行，长文件用 `offset` / `limit` 分段读），`edit_file` 精确替换（`old_text` 必须唯一，否则不修改；`replace_all` 替换全部），`write_file` 写文件，`shell` 只用于构建、测试、git 等命令（输出超过上限时保留开头和结尾）。

每次会话开始时，hu 把项目上下文附加到系统提示词，模型从第一轮起就知道自己是谁、在哪个项目里：

- 工作目录、平台、shell 工具的语法、日期；
- git 仓库里的当前分支、工作区状态和最近 5 次提交（会话开始时的快照）；
- 说明文件：`~/.hu-agent/AGENTS.md`（对所有项目生效），以及从仓库根目录到工作目录逐层的 `AGENTS.md`（没有时读同目录的 `CLAUDE.md`，可以和 Claude Code 共用一份）。本仓库自己的说明见 [AGENTS.md](AGENTS.md)。

系统提示词的工作方法部分改编自 [opencode](https://github.com/sst/opencode) 为开源模型写的提示词和 [oh-my-pi](https://github.com/can1357/oh-my-pi) 的工作流程约定（均为 MIT 许可，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)），最重要的几条在提示词末尾再重述一次。另外借鉴 oh-my-pi 对较弱模型的两条纠偏：模型返回空回复时提醒它继续（每轮最多两次，仍然为空时提示本轮没有回答，空回复不记入历史）；连续三次用相同参数调用同一工具时，在结果后提醒它换个做法。

每轮模型调用上限（`agent.max-model-calls`，默认 200）只是防止失控的安全网；用到最后一次时，hu 会告诉模型工具不会再执行，请它总结已完成的工作和剩余步骤，而不是直接丢掉最后一批工具调用。上下文压缩默认按 `max-context-tokens` 换算阈值，对话超过阈值一半之前保留全部工具结果的原文。模型请求不是流式的，一次请求最多等 10 分钟（与 Claude Code 相同，覆盖模型思考和写大段编辑的时间），连接最多等 30 秒；等待期间随时可以按 Esc 中断。

### 项目级配置

当前目录下的 `.agents/` 是项目级配置：

| 文件 | 作用 | 生效条件 |
| --- | --- | --- |
| `.agents/hook.json` | 命令 Hook | 首次使用或内容变化时需确认授权 |
| `.agents/mcp.json` | stdio MCP server | 首次使用或内容变化时需确认授权 |
| `.agents/skills/<名称>/SKILL.md` | 技能 | 也可以用 `/<名称>` 直接调用 |

本仓库自带示例：`.agents/hook.example.json`、`.agents/mcp.example.json`、`.agents/skills/`。

会话数据也写在当前目录，用到相应功能时才会创建：`.memory/`、`.tasks/`、`.schedules/`、`.workflows/`、`.transcripts/` 等。建议加入项目的 `.gitignore`。

## 开发

需要 JDK 25 和 Maven。Windows 可以用 `./mvnw.cmd` 代替 `mvn`；注意让 `JAVA_HOME` 指向 JDK 25。

```sh
mvn test                    # 全部测试（不需要 API Key，模型接口使用本地模拟服务）
mvn package                 # 生成 target/hu-agent.jar（含依赖）以及发布包 target/hu-agent.zip、target/hu-agent.tar.gz
java -jar target/hu-agent.jar        # 直接运行，配置读 ~/.hu-agent/model.properties 与 config.properties
mvn -q compile exec:java             # 从源码运行
hu update --local                    # 在源码目录执行：构建（跳过测试）并把当前源码安装为 hu 命令
hu update --local ~/coding/hu-agent  # 在任意目录执行：构建并安装指定目录的源码（相对路径按当前目录解析）
```

`hu update`（包括 `--local`、`--pkg`）成功时只显示“更新中”和“更新完成”。失败时返回非零退出码，先显示这次构建、下载和安装的全部输出，最后一行说明原因，例如 hu 仍在运行时的 `更新失败：Cannot replace …\app. Exit every running hu and try again.`；没有明确原因（如构建失败）时最后一行是“更新失败”，原因看上面的构建输出。想边执行边看输出（成功时也显示）时，使用 `hu update --debug`（或 `-d`）；调试选项放在更新来源参数前，例如 `hu update --debug --local`。

`hu update --local` 用仓库里的安装脚本安装 `target` 下的发布包，结果与正式安装相同，只是来源换成本地构建；之后 `hu update` 会回到最新发布版。构建要用 `JAVA_HOME` 指定的 JDK 25：hu 自带的 Java 是 JRE，只能运行 hu、不能编译。`JAVA_HOME` 是旧版 JDK 或指向 JRE 时，失败的最后一行会提示先指定 JDK 25，例如 `$env:JAVA_HOME = 'C:\path\to\jdk-25'`（macOS / Linux 为 `export JAVA_HOME=/path/to/jdk-25`）。

入口是 `dev.huagent.HuMain`。和教程 S18 相比，改动在这些地方：

| 位置 | 改动 |
| --- | --- |
| `config.HuHome` | 用户目录。启动脚本传入 `-Dhu.home`，其次读 `HU_HOME`，默认 `~/.hu-agent` |
| `config.ProjectDirectory` | 项目目录就是当前目录，替代按编译产物定位源码工程 |
| `config.ModelConfig` / `config.ModelProvider` | 模型接口配置单独成文件 `model.properties`，四种接口类型（OpenAI / Anthropic，各分官方与自定义地址）；`HU_*` 环境变量覆盖 |
| `config.AgentConfig` | 模型部分交给 `ModelConfig`，其余设置读 `config.properties`；工作目录默认为当前目录。教程各阶段仍可用单个文件里的 `openai.*` |
| `config.ConfigSetup` | 首次运行引导：选择接口类型并填写，按 `model.example.properties` 模板写入，`config.properties` 不存在时按模板一并生成 |
| `model.AnthropicClient` / `model.ModelClients` | Anthropic Messages 协议客户端（对话历史在发送前转换为 content block）；按接口类型创建客户端 |
| `config.HuVersion` | 版本号，构建时由 Maven 写入 |
| `HookTrustStore` / `McpTrustStore` | 授权记录改存到 `HuHome` 下 |
| `context.ProjectContext` | 会话开始时附加到系统提示词的项目上下文：身份、环境、git 状态、`AGENTS.md` / `CLAUDE.md` |
| `tool.file.GrepTool` | 按内容搜索（对应 Claude Code 的 Grep）；`read_file` 带行号并支持 `offset`，`edit_file` 要求匹配唯一 |
| `prompt.LineEditor` | 交互终端用 JLine 读输入：斜杠命令列表（`SlashMenu`，可滚动，列表显示期间才开启鼠标跟踪）与输入历史；读取期间的后台、队友输出插在输入行上方。输入输出被重定向时仍按行读取 |

发布包结构：`hu-agent/bin/hu`、`hu-agent/bin/hu.cmd`、`hu-agent/lib/hu-agent.jar`，以及 `hu-agent/install.sh`、`hu-agent/install.ps1`（供 `hu update --pkg` 离线使用）。启动脚本在 [src/dist/bin](src/dist/bin)，打包配置在 [src/assembly/dist.xml](src/assembly/dist.xml)。

## 发布

```sh
git tag v0.1.0
git push origin v0.1.0
```

推送标签后，[release 工作流](.github/workflows/release.yml) 会依次执行：

1. 用标签作为版本号构建并跑全部测试；
2. 以 prerelease 上传发布包和 sha256；
3. 在 Linux x64、Linux arm64、macOS、Windows 上用真实安装命令安装，并做冒烟测试；
4. 全部通过后才标记为 latest。

验证失败的版本不会被默认安装。

升级锁定的 JRE 时，同时修改 `install.sh` 和 `install.ps1` 里的版本号与 sha256。sha256 可以从下面的接口取，看每个平台的 `binary.package.checksum`：

```text
https://api.adoptium.net/v3/assets/release_name/eclipse/<版本，如 jdk-25.0.5%2B8>?image_type=jre
```
