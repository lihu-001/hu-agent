"""Minimal stdio MCP server example for S14. Requires Python 3; uses only its standard library.

Each line on stdin is one JSON-RPC message; each reply is one line on stdout.
Logs go to stderr: stdout is reserved for protocol messages.
"""

import datetime
import json
import sys

sys.stdin.reconfigure(encoding="utf-8")
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

TOOLS = [
    {
        "name": "word_count",
        "description": "统计一段文本的字符数、行数和空白分隔的词数。只读，不访问文件。",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string", "description": "要统计的文本"}},
            "required": ["text"],
        },
    },
    {
        "name": "now",
        "description": "返回 server 所在机器的当前本地时间（ISO 8601）。",
        "inputSchema": {"type": "object", "properties": {}},
    },
]


def call(name, arguments):
    if name == "word_count":
        text = arguments.get("text")
        if not isinstance(text, str):
            return {"content": [{"type": "text", "text": "text 必须是字符串"}], "isError": True}
        result = f"字符 {len(text)}，行 {len(text.splitlines()) or 0}，词 {len(text.split())}"
        return {"content": [{"type": "text", "text": result}], "isError": False}
    if name == "now":
        now = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
        return {"content": [{"type": "text", "text": now}], "isError": False}
    return None


print("example MCP server started", file=sys.stderr, flush=True)
for line in sys.stdin:
    if not line.strip():
        continue
    message = json.loads(line)
    if "id" not in message or "method" not in message:
        continue  # notifications such as notifications/initialized
    method = message["method"]
    params = message.get("params") or {}
    reply = {"jsonrpc": "2.0", "id": message["id"]}
    if method == "initialize":
        reply["result"] = {
            "protocolVersion": params.get("protocolVersion", "2025-06-18"),
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "s14-example", "version": "1.0"},
        }
    elif method == "tools/list":
        reply["result"] = {"tools": TOOLS}
    elif method == "tools/call":
        result = call(params.get("name"), params.get("arguments") or {})
        if result is None:
            reply["error"] = {"code": -32602, "message": f"Unknown tool: {params.get('name')}"}
        else:
            reply["result"] = result
    elif method == "ping":
        reply["result"] = {}
    else:
        reply["error"] = {"code": -32601, "message": "Method not found"}
    print(json.dumps(reply, ensure_ascii=False), flush=True)
