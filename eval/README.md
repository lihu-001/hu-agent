# hu 编程评测

用 `hu -p` 跑一组小型编程任务并自动判定，用来比较改动前后 hu 完成真实修改的能力（提示词、工具、上下文压缩等）。单元测试只能说明代码按预期工作，说明不了模型配合这些代码能不能把事情做成。

**会请求真实模型，产生费用。** 不在 `mvn test` 和 CI 中运行，需要手动启动。

## 用法

需要 Python 3.9+。在本目录的上一级（仓库根目录）运行：

```sh
python eval/run.py --self-check                          # 不调用模型：确认各任务的判定代码是对的
python eval/run.py --output eval-results/2026-11-02      # 用 PATH 上的 hu 跑全部任务
python eval/run.py --task crossfile --repeat 3 --output eval-results/cross
python eval/run.py --hu ~/.hu-agent/app/bin/hu --output eval-results/x   # 指定 hu（Windows 为 hu.cmd）
```

- 模型与接口用 hu 自己的配置（`~/.hu-agent/model.properties` 或 `HU_*` 环境变量），想评测别的模型时用环境变量覆盖，例如 `HU_MODEL=其他模型 python eval/run.py ...`。
- 要评测改过的源码，先 `hu update --local` 安装再运行。
- `--output` 必须是不存在的新目录。模型结果有波动，比较两个版本时每个任务至少跑 3 次（`--repeat 3`）。

## 结果

`--output` 目录里：

| 文件 | 内容 |
| --- | --- |
| `results.json` | `summary`（通过数、总数、总耗时）与每次运行的 `status`、`hu_exit_code`、`tool_calls`、`duration_seconds`；每次运行结束即写入 |
| `<任务>.stdout.log` | hu 的最终回答 |
| `<任务>.stderr.log` | 过程：中间说明、每次工具调用一行 `工具> …`，排查失败原因先看这里 |
| `<任务>.check.log` | 判定输出（断言失败的位置） |

`--repeat` 大于 1 时第二次起文件名为 `<任务>.<次数>.*`。`status`：`pass` 通过，`fail` 判定不通过，`timeout` 超过 `--timeout`（默认 600 秒），`error` 启动失败。hu 没有给出最终回答（如达到调用上限）时照样判定，`hu_exit_code` 记下退出码。

## 任务

| 任务 | 考察 |
| --- | --- |
| `slug`、`csv`、`unique` | 单文件按说明修复，边界情况（空输入、CSV 引号、不可哈希元素） |
| `crossfile` | 只描述现象（测试失败），要从测试顺着调用找到另一个文件里的原因，改完自己运行测试；测试文件和无关文件不许改 |
| `longfile` | 目标函数在两千多行文件的第 2301 行，默认的 `read_file` 读不到，要用 `grep` 或 `offset`；整个文件重写而丢掉其他函数算失败 |

每个任务在新建的临时目录里运行，结束后删除，不碰本仓库。判定代码由评测器持有，不放进工作目录，模型看不到也改不了；判定以 `python -I` 运行，工作目录里的同名模块不会顶替标准库。

加任务：在 `run.py` 的 `TASKS` 里写 `prompt`、`files`（初始文件）、`solution`（参考修复，只用于 `--self-check`）、`checks`（判定代码，退出码 0 为通过），需要时加 `unchanged`（不许修改的文件）。加完先跑 `--self-check`：初始文件必须判为失败、参考修复必须判为通过。

## 安全

hu 默认自动审批，评测中模型可以执行任意 Shell 命令；文件工具限制在工作目录内，Shell 不受这个限制，也没有沙箱。只用可信的模型和接口，在自己的机器或一次性的容器里运行。需要逐次拒绝时可以给 hu 加 `--no-auto`（`-p` 下没有人回答，确认一律拒绝），但多数任务会因此做不完。
