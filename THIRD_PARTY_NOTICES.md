# 第三方声明

hu-agent 的部分提示词改编自以下 MIT 许可的项目（译成中文并按 hu 的工具改写）：

| 项目 | 改编内容 | 位置 |
| --- | --- | --- |
| [opencode](https://github.com/sst/opencode)（`fa1260d`） | `packages/core/src/plugin/system-prompt/kimi.txt`、`packages/core/src/session/runner/prompt/system.txt`、`gpt.txt` 中的工作方法 | `app.ToolUseMain.SYSTEM_PROMPT` |
| [oh-my-pi](https://github.com/can1357/oh-my-pi)（`c3b645c`） | `system-prompt.md` 的工作流程与交付约定；`tool-call-loop-redirect.md`、`empty-stop-retry.md` 的纠偏提示；提示词写作规范（关键规则首尾重述、正面表述、工具说明带示例） | `app.ToolUseMain.SYSTEM_PROMPT`、`context.ProjectContext.CRITICAL`、`loop.ChatLoop`、各工具说明 |

## opencode

```
MIT License

Copyright (c) 2025 opencode

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## oh-my-pi

```
MIT License

Copyright (c) 2025 Mario Zechner
Copyright (c) 2025-2026 Can Bölük
Copyright (c) 2026 Stencil Labs, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
