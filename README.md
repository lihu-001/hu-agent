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

- 输入 `/` 会在输入行下方列出命令，继续输入即时过滤；一次最多显示 10 个，↑↓ 选择时列表跟着滚动，也可以用鼠标滚轮滚动；Tab 填入选中的命令（未选中时为第一个），回车在选中后填入，再回车执行；
- 不以 `/` 开头时 ↑↓ 翻看本次会话的输入历史；有内容时 Ctrl+C 清空当前行，空行上 Ctrl+C 或 Ctrl+D 退出；
- `/help`：查看全部命令；
- `/model`：列出当前模型、常用模型与接口提供的全部模型；输入 `/model ` 加空格后在下方列出这些模型，继续输入按名称过滤，↑↓ 选择后回车填入、再回车切换；`/model <名称>`：切换同一接口下的模型，不清空对话，并写回配置文件作为默认；
- `/status`、`/tools`、`/skills`、`/mcp`：查看状态；
- `/clear`：清空对话；
- `/compact`：立即压缩上下文；
- `/goal <条件>`：设定目标，未达成时自动续跑；
- `exit`：退出。

`/model` 的模型列表：`model.properties` 里 `models=` 列出的常用模型排在最前（逗号分隔，可选），其后是接口 `<base-url>/models` 返回的全部模型（Anthropic 协议为 `/v1/models`）。接口列表在会话开始时后台查询一次，不影响启动；接口不提供模型列表时只列常用模型。

`/model <名称>` 切换后写回启动时读取模型的文件（`model.properties` 的 `model`；旧版写法写 `config.properties` 的 `openai.model`），只改这一行，下次启动沿用。为了不把拼错的名字留到下次启动，只有常用模型或接口列表里有的模型才写回，其他名称只在本次会话生效；设置了环境变量 `HU_MODEL` 时也只在本次会话生效。接口、密钥与 token 上限（`max-context-tokens`、`max-output-tokens`）不随模型变化。

切换后，共用客户端的子代理、队友、上下文压缩与默认目标判断器从后续请求起使用新模型；已发出的请求和 `goal.evaluator-model` 单独指定的判断器不变。`/status` 与输入栏同步显示当前模型；旧模型的上下文用量清除，等待新请求返回 usage 后更新。命令本身不请求模型、不触发 Hook、不进入对话历史。

`hu --version` 查看版本，`hu --help` 查看用法。

启动时先显示启动页：渐变色像素字 `hu`、版本和一条随机使用提示。颜色只在能解释 ANSI 转义的终端里显示（macOS / Linux 终端，Windows 上的 Windows Terminal、VS Code 终端、ConEmu），传统控制台窗口里不带颜色；设置环境变量 `NO_COLOR` 可关闭颜色。

默认只显示对话、权限确认和每轮的最终回答：工具调用、工具结果以及与工具调用一起返回的中间回复都不显示（模型收到的仍是完整结果）。权限确认压成以【权限确认】开头的一行（终端支持时为黄色），如 `【权限确认】shell：git status  仅允许本次调用？[y/N]`；参数只有一个字符串时直接显示它，否则显示一行 JSON，参数很长时问题另起一行。排查问题时用 `hu --debug`（或 `-d`）启动，会额外显示每次模型请求与耗时、内置 Hook 日志、微压缩、启动清单、中间回复、工具调用（含 ID 与完整参数）和完整工具结果，权限确认恢复为原因、工具与调用 ID、参数、问题各一行。

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
```

`hu update --local` 用仓库里的安装脚本安装 `target` 下的发布包，结果与正式安装相同，只是来源换成本地构建；之后 `hu update` 会回到最新发布版。

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
