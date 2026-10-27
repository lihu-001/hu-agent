"""Optional JSON command-hook example. Requires Python 3; uses only its standard library."""

import json
import sys

sys.stdin.reconfigure(encoding="utf-8")
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")
event = json.load(sys.stdin)
name = event["hook_event_name"]

if name == "UserPromptSubmit" and event["prompt"].strip() == "取消":
    print("本次提示词已取消。")
    sys.exit(2)

if name == "PreToolUse" and event["tool_name"] == "shell":
    print("示例策略：禁止本次 Shell 调用。")
    sys.exit(2)

if name == "PostToolUse":
    print(f"工具 {event['tool_name']} 已完成，耗时 {event['elapsed_ms']} ms。")
elif name == "Stop":
    print(f"本轮正常完成，共 {len(event['turn'])} 条消息。")
else:
    print(f"{name} 检查通过。")
