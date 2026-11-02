#!/usr/bin/env python3
"""用 hu -p 跑一组小型编程任务并自动判定，衡量 hu 完成真实修改的能力。

每个任务在新建的临时目录里运行，不碰本仓库；判定代码由评测器持有，不放进模型能修改的工作目录。
会请求真实模型（产生费用），不在 mvn test 或 CI 中运行。用法见同目录的 README.md。
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time


def _legacy_source(fixed):
    """长文件任务：parse_duration 埋在两千多行里，默认的 read_file 读不到，要用 grep 或 offset 找。"""
    filler = "".join(f'def helper_{i}(value):\n    """兼容旧接口的第 {i} 个转换函数。"""\n    return value + {i}\n\n\n'
                     for i in range(500))
    reset = '            number = ""\n' if fixed else ""
    target = ('def parse_duration(text):\n'
              '    """把 "1h30m15s"、"45s"、"2h" 这样的时长解析成秒数；h、m、s 可以组合，顺序固定。"""\n'
              '    total = 0\n'
              '    number = ""\n'
              '    for ch in text:\n'
              '        if ch.isdigit():\n'
              '            number += ch\n'
              '        elif ch == "h":\n'
              '            total += int(number) * 3600\n'
              '            number = ""\n'
              '        elif ch == "m":\n'
              '            total += int(number) * 60\n'
              + reset +
              '        elif ch == "s":\n'
              '            total += int(number)\n'
              '            number = ""\n'
              '        else:\n'
              '            raise ValueError(text)\n'
              '    return total\n\n\n')
    split = filler.index("def helper_460(")
    return filler[:split] + target + filler[split:]


_CART = '''from shop.pricing import apply_discount, line_total


class Cart:
    def __init__(self):
        self.items = []

    def add(self, name, price_cents, quantity=1, percent=0):
        self.items.append((name, price_cents, quantity, percent))

    def total(self):
        return sum(line_total({price}, quantity, percent)
                   for _, price, quantity, percent in self.items)
'''

_CART_TEST = '''import unittest

from shop.cart import Cart


class CartTest(unittest.TestCase):
    def test_total_without_discount(self):
        cart = Cart()
        cart.add("笔", 250, 2)
        self.assertEqual(500, cart.total())

    def test_total_with_discount(self):
        cart = Cart()
        cart.add("书", 1000, 1, percent=20)
        self.assertEqual(800, cart.total())
'''

# 判定代码以 python -I 运行（工作目录里的同名模块不会顶替标准库）；需要导入工作目录里的包时追加到 sys.path 末尾。
_LOAD = """import importlib.util, os, pathlib, sys
def load(name):
    spec = importlib.util.spec_from_file_location('subject', pathlib.Path.cwd() / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
"""

# files：交给 hu 的初始文件；solution：参考修复（只用于 --self-check，不会出现在工作目录里）；
# unchanged：不允许修改的文件；checks：判定代码，在工作目录里运行，退出码 0 为通过。
TASKS = {
    "slug": {
        "prompt": "修复 slug.py 中的 slugify：去除首尾空白，转小写，将连续空白替换为单个连字符，其他字符保留。不要添加依赖。",
        "files": {"slug.py": "def slugify(text):\n    return text.lower().replace(' ', '-')\n"},
        "solution": {"slug.py": "import re\n\n\ndef slugify(text):\n    return re.sub(r'\\s+', '-', text.strip().lower())\n"},
        "checks": _LOAD + """m = load('slug.py')
for value, expected in [(' Hello World ', 'hello-world'), ('A  B\\tC', 'a-b-c'), ('', ''), (' déjà vu! ', 'déjà-vu!'), ('a-b', 'a-b')]:
    assert m.slugify(value) == expected, repr(value)
""",
    },
    "csv": {
        "prompt": "修复 totals.py 的 totals 函数。输入为 CSV 文本，首行是 name,amount；将同名的整数 amount 求和，返回名称到整数总数的字典。支持 CSV 引号、空输入及只有表头，不添加依赖。",
        "files": {"totals.py": "def totals(text):\n    result = {}\n    for line in text.splitlines()[1:]:\n        name, amount = line.split(',')\n        result[name] = int(amount)\n    return result\n"},
        "solution": {"totals.py": "import csv\nimport io\n\n\ndef totals(text):\n    result = {}\n    for row in csv.DictReader(io.StringIO(text)):\n        result[row['name']] = result.get(row['name'], 0) + int(row['amount'])\n    return result\n"},
        "checks": _LOAD + """m = load('totals.py')
for value, expected in [('', {}), ('name,amount\\n', {}), ('name,amount\\na,2\\na,-1\\nb,4\\n', {'a': 1, 'b': 4}), ('name,amount\\n"a,b",3\\n"a,b",2\\n', {'a,b': 5})]:
    assert m.totals(value) == expected, repr(value)
""",
    },
    "unique": {
        "prompt": "修复 unique.py 的 unique 函数：返回去重后的新列表，保持第一次出现的顺序，不修改输入；元素可能是不可哈希的列表。不添加依赖。",
        "files": {"unique.py": "def unique(items):\n    return list(set(items))\n"},
        "solution": {"unique.py": "def unique(items):\n    result = []\n    for item in items:\n        if item not in result:\n            result.append(item)\n    return result\n"},
        "checks": _LOAD + """m = load('unique.py')
for value, expected in [([], []), ([3, 1, 3, 2, 1], [3, 1, 2]), ([[1], [2], [1]], [[1], [2]])]:
    before = repr(value); result = m.unique(value)
    assert result == expected and repr(value) == before and result is not value
""",
    },
    "crossfile": {
        # 只描述现象，不说在哪个文件：要从失败的测试顺着 cart.py 找到原因，改完自己跑测试确认。
        "prompt": "这个项目运行 python -m unittest 时有一个测试失败：带折扣的商品算出的总价不对。找出原因并修复，修好后运行测试确认通过；不要修改 tests/ 下的测试。",
        "files": {
            "shop/__init__.py": "",
            "shop/pricing.py": 'def apply_discount(price_cents, percent):\n    """打折后的单价（分）：percent=20 表示减 20%，四舍五入到分。"""\n    return round(price_cents * (100 - percent) / 100)\n\n\ndef line_total(price_cents, quantity, percent=0):\n    """一行商品的小计（分）。"""\n    return apply_discount(price_cents, percent) * quantity\n',
            "shop/cart.py": _CART.replace("{price}", "apply_discount(price, percent)"),
            "tests/__init__.py": "",
            "tests/test_cart.py": _CART_TEST,
        },
        "solution": {"shop/cart.py": _CART.replace("{price}", "price")},
        "unchanged": ["tests/test_cart.py", "shop/pricing.py"],
        "checks": """import os, sys
sys.path.append(os.getcwd())
from shop.cart import Cart
cart = Cart()
cart.add('a', 999, 3, percent=10)
cart.add('b', 100)
assert cart.total() == 899 * 3 + 100, cart.total()
""",
    },
    "longfile": {
        "prompt": "legacy.py 里的 parse_duration('1h30m15s') 返回的结果不对，应该是 5415 秒。修复这个问题，文件里的其他函数保持不变。",
        "files": {"legacy.py": _legacy_source(False)},
        "solution": {"legacy.py": _legacy_source(True)},
        "checks": _LOAD + """m = load('legacy.py')
for value, expected in [('1h30m15s', 5415), ('45s', 45), ('2h', 7200), ('10m', 600), ('1h5s', 3605), ('2m3s', 123)]:
    assert m.parse_duration(value) == expected, repr(value)
# 整个文件重写时容易丢掉其他函数。
assert all(getattr(m, f'helper_{i}')(1) == 1 + i for i in range(500))
""",
    },
}


def command_for(path):
    resolved = shutil.which(path) if not Path(path).is_absolute() else path
    if not resolved or not Path(resolved).is_file():
        raise ValueError(f"找不到 hu 可执行文件：{path}")
    # Windows 的 .cmd/.bat 需要命令解释器；任务从标准输入传入，不经过命令行转义。
    if os.name == "nt" and Path(resolved).suffix.lower() in (".cmd", ".bat"):
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", str(resolved)]
    return [str(resolved)]


def prepare(work, files):
    for path, content in files.items():
        target = work / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8", newline="\n")


def judge(task, work, timeout):
    """返回 (是否通过, 判定输出)。不允许修改的文件被改了直接算失败。"""
    for path in task.get("unchanged", []):
        current = work / path
        if not current.is_file() or current.read_text(encoding="utf-8") != task["files"][path]:
            return False, f"不允许修改的文件被改动：{path}\n".encode("utf-8")
    check = subprocess.run([sys.executable, "-I", "-c", task["checks"]], cwd=work, stdin=subprocess.DEVNULL,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    return check.returncode == 0, check.stdout


def run_task(name, task, command, timeout, check_timeout, output, attempt):
    started = time.monotonic()
    result = {"task": name, "attempt": attempt, "status": "error", "hu_exit_code": None, "tool_calls": None}
    label = name if attempt == 1 else f"{name}.{attempt}"
    with tempfile.TemporaryDirectory(prefix="hu-eval-") as tmp:
        work = Path(tmp) / "work"
        work.mkdir()
        prepare(work, task["files"])
        try:
            # 不带任务参数时 hu -p 按 UTF-8 读标准输入。
            proc = subprocess.run(command + ["-p"], cwd=work, input=task["prompt"].encode("utf-8"),
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
            result["hu_exit_code"] = proc.returncode
            (output / (label + ".stdout.log")).write_bytes(proc.stdout)
            (output / (label + ".stderr.log")).write_bytes(proc.stderr)
            # 过程写在标准错误，每次工具调用一行 "工具> …"。
            result["tool_calls"] = sum(1 for line in proc.stderr.decode("utf-8", "replace").splitlines()
                                       if line.startswith("工具> "))
            # 没有最终回答（如达到调用上限）也照样判定：改动可能已经完成。
            passed, log = judge(task, work, check_timeout)
            (output / (label + ".check.log")).write_bytes(log)
            result["status"] = "pass" if passed else "fail"
        except subprocess.TimeoutExpired:
            result["status"] = "timeout"
        except OSError as exc:
            result["error"] = str(exc)
    result["duration_seconds"] = round(time.monotonic() - started, 3)
    return result


def self_check(names, check_timeout):
    """不调用 hu：初始文件应判为失败，换上参考修复后应判为通过，用来确认判定代码本身是对的。"""
    ok = True
    for name in names:
        task = TASKS[name]
        with tempfile.TemporaryDirectory(prefix="hu-eval-self-") as tmp:
            work = Path(tmp)
            prepare(work, task["files"])
            before, before_log = judge(task, work, check_timeout)
            prepare(work, task["solution"])
            after, after_log = judge(task, work, check_timeout)
        good = not before and after
        ok = ok and good
        print(f"{name}: {'ok' if good else '判定有误'}（初始文件{'通过' if before else '失败'}，参考修复{'通过' if after else '失败'}）")
        if not after:
            print(after_log.decode("utf-8", "replace"))
    return 0 if ok else 1


def main():
    parser = argparse.ArgumentParser(description="在临时目录中运行 hu -p 编程评测")
    parser.add_argument("--hu", default="hu", help="hu 可执行文件路径，默认从 PATH 查找")
    parser.add_argument("--task", choices=sorted(TASKS), action="append", help="只运行指定任务，可重复")
    parser.add_argument("--repeat", type=int, default=1, help="每个任务运行几次（模型结果有波动），默认 1")
    parser.add_argument("--timeout", type=int, default=600, help="每次 hu 调用超时秒数，默认 600")
    parser.add_argument("--check-timeout", type=int, default=30, help="每次自动判定超时秒数，默认 30")
    parser.add_argument("--output", type=Path, help="新建结果目录，必须不存在")
    parser.add_argument("--self-check", action="store_true", help="不调用 hu，只验证各任务的判定代码")
    args = parser.parse_args()
    names = list(dict.fromkeys(args.task or TASKS))
    if args.self_check:
        return self_check(names, args.check_timeout)
    if args.output is None:
        parser.error("需要 --output")
    if args.timeout <= 0 or args.check_timeout <= 0 or args.repeat <= 0:
        parser.error("超时与次数必须大于零")
    try:
        command = command_for(args.hu)
        output = args.output.resolve()
        output.mkdir(parents=False, exist_ok=False)
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    results = []
    for name in names:
        for attempt in range(1, args.repeat + 1):
            result = run_task(name, TASKS[name], command, args.timeout, args.check_timeout, output, attempt)
            results.append(result)
            print(f"{name}#{attempt}: {result['status']}（{result['duration_seconds']} 秒，"
                  f"工具调用 {result['tool_calls']} 次）", flush=True)
            # 每次结束即落盘，中途停止也保留已有结果。
            passed = sum(1 for r in results if r["status"] == "pass")
            summary = {"passed": passed, "total": len(results),
                       "duration_seconds": round(sum(r["duration_seconds"] for r in results), 3)}
            (output / "results.json").write_text(
                json.dumps({"version": 2, "summary": summary, "results": results}, ensure_ascii=False, indent=2)
                + "\n", encoding="utf-8")
    print(f"通过 {passed}/{len(results)}，结果在 {output}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
