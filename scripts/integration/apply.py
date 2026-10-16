#!/usr/bin/env python3
"""补丁应用器（集成阶段静态改造用）。

用法：
    python3 scripts/integration/apply.py ops/review.py [--dry-run] [--root DIR]

ops 文件为 Python 模块，需定义 OPS = [ ... ]，每个元素是元组：
    ("create",        rel_path, content)
    ("write",         rel_path, content)                 # 整体覆盖
    ("replace",       rel_path, old, new, count)         # count: 期望出现次数，或 "all"
    ("insert_after",  rel_path, anchor, text, count)
    ("insert_before", rel_path, anchor, text, count)
    ("append",        rel_path, text)
    ("delete",        rel_path, text, count)

锚点定位策略（按顺序尝试）：
    1. exact         —— 原样子串匹配
    2. line-trim     —— 逐行去掉首尾空白后拼接匹配（保留原文件的缩进用于插入）
    3. dedent        —— 整块 dedent 后匹配（针对 ops 里缩进与原文件不一致的情况）

每个操作都会输出 OK / SKIP / FAIL，并写入 reports/<patch>.json 便于汇总。
锚点不唯一（出现次数 != 期望）时 **不修改文件**，直接报冲突，由人工消解。
"""

import argparse
import json
import os
import re
import sys
import textwrap

REPORT_ROWS = []


CACHE = {}


def read(path):
    if path in CACHE:
        return CACHE[path]
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def write(path, text):
    # 内存缓存保证 dry-run 与实跑的操作序一致（同一文件多点修改时会互相影响锚点计数）。
    CACHE[path] = text
    if DRY_RUN:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


DRY_RUN = False


def count_occurrences(hay, needle):
    if not needle:
        return 0
    return hay.count(needle)


def find_spans(hay, needle):
    spans = []
    start = 0
    while True:
        i = hay.find(needle, start)
        if i < 0:
            return spans
        spans.append((i, i + len(needle)))
        start = i + len(needle)


def logical_lines(text):
    """返回 [(start_offset, end_offset_without_newline, stripped_text)]。"""
    out = []
    pos = 0
    for line in text.split("\n"):
        out.append((pos, pos + len(line), line.strip()))
        pos += len(line) + 1
    return out


def locate_line_trim(hay, needle):
    """逐行 strip 后定位锚点。返回 [(start, end, start_line_idx, end_line_idx)]。"""
    h_lines = logical_lines(hay)
    n_lines = [(line.strip()) for line in needle.split("\n")]
    while n_lines and n_lines[0] == "":
        n_lines.pop(0)
    while n_lines and n_lines[-1] == "":
        n_lines.pop()
    if not n_lines:
        return []
    h_stripped = [l[2] for l in h_lines]
    hits = []
    for i in range(0, len(h_stripped) - len(n_lines) + 1):
        if h_stripped[i:i + len(n_lines)] == n_lines:
            start = h_lines[i][0]
            end = h_lines[i + len(n_lines) - 1][1]
            hits.append((start, end, i, i + len(n_lines) - 1))
    return hits


def locate(hay, needle):
    """返回 (strategy, spans)。spans 形如 [(start, end, first_line, last_line)]。"""
    hits = find_spans(hay, needle)
    if hits:
        lmap = logical_lines(hay)
        spans = []
        for (s, e) in hits:
            fl = last = 0
            for idx, (ls, le, _) in enumerate(lmap):
                if ls <= s:
                    fl = idx
                if le <= e:
                    last = idx
            spans.append((s, e, fl, last))
        return "exact", spans

    trimmed = locate_line_trim(hay, needle)
    if trimmed:
        return "line-trim", trimmed

    ded = textwrap.dedent(needle)
    if ded != needle:
        hits = find_spans(hay, ded)
        if hits:
            lmap = logical_lines(hay)
            spans = []
            for (s, e) in hits:
                fl = last = 0
                for idx, (ls, le, _) in enumerate(lmap):
                    if ls <= s:
                        fl = idx
                    if le <= e:
                        last = idx
                spans.append((s, e, fl, last))
            return "dedent", spans
        # dedent + line-trim 组合
        h_lines = logical_lines(hay)
        n_lines = [l.strip() for l in ded.split("\n")]
        while n_lines and n_lines[0] == "":
            n_lines.pop(0)
        while n_lines and n_lines[-1] == "":
            n_lines.pop()
        h_stripped = [l[2] for l in h_lines]
        for i in range(0, len(h_stripped) - len(n_lines) + 1):
            if h_stripped[i:i + len(n_lines)] == n_lines:
                spans = [(h_lines[i][0], h_lines[i + len(n_lines) - 1][1], i, i + len(n_lines) - 1)]
                return "dedent+line-trim", spans
    return None, []


def indentation_of(hay, offset):
    """取 offset 所在行的前导空白。"""
    ls = hay.rfind("\n", 0, offset) + 1
    line = hay[ls:hay.find("\n", ls) if hay.find("\n", ls) >= 0 else len(hay)]
    m = re.match(r"[ \t]*", line)
    return m.group(0) if m else ""


def extract_block(patch_path, fence_line):
    """从补丁文档中抽取以 fence_line（1-based，须为 ``` 开头行）起始的代码块内容。"""
    lines = read(patch_path).split("\n")
    i = fence_line - 1
    if i >= len(lines) or not lines[i].lstrip().startswith("```"):
        raise ValueError("第 %d 行不是代码围栏：%r" % (fence_line, lines[i][:40] if i < len(lines) else ""))
    out = []
    j = i + 1
    while j < len(lines) and not lines[j].lstrip().startswith("```"):
        out.append(lines[j])
        j += 1
    if j >= len(lines):
        raise ValueError("第 %d 行的代码块未闭合" % fence_line)
    return "\n".join(out) + "\n", j + 1


def apply_op(root, op, dry_run):
    kind = op[0]

    # ---- 补丁文档代码块抽取族：x_* 直接从 delivery/patches/*.md 的围栏块取内容，避免手工转写失真 ----
    if kind in ("extract", "x_write", "x_append", "x_replace", "x_replace_lit",
                "x_replace_lit2", "x_insert_after", "x_insert_before", "x_insert_after_f",
                "x_insert_before_f", "x_delete"):
        patch = os.path.join(root, op[1])
        if kind == "extract":
            content, _ = extract_block(patch, op[2])
            dest = op[3]
            path = os.path.join(root, dest)
            if os.path.exists(path) or path in CACHE:
                return ("SKIP", dest, "文件已存在（extract 跳过）")
            write(path, content)
            return ("OK", dest, "extract %d bytes (%s:%d)" % (len(content), op[1], op[2]))

        if kind == "x_write":
            content, _ = extract_block(patch, op[2])
            dest = op[3]
            write(os.path.join(root, dest), content)
            return ("OK", dest, "x_write %d bytes (%s:%d)" % (len(content), op[1], op[2]))

        if kind == "x_append":
            content, _ = extract_block(patch, op[2])
            dest = op[3]
            path = os.path.join(root, dest)
            write(path, read(path) + content)
            return ("OK", dest, "x_append %d bytes (%s:%d)" % (len(content), op[1], op[2]))

        if kind in ("x_replace", "x_replace_lit", "x_replace_lit2"):
            content, _ = extract_block(patch, op[2])
            if kind == "x_replace":
                old, _ = extract_block(patch, op[3])
                dest = op[4]
                expect = op[5] if len(op) > 5 else 1
                block = content.rstrip("\n")
            elif kind == "x_replace_lit":
                dest = op[3]
                old = op[4]
                expect = op[5] if len(op) > 5 else 1
                block = content.rstrip("\n")
            else:
                # x_replace_lit2: (patch, new_fence, dest, old, prefix, suffix, count)
                dest = op[3]
                old = op[4]
                prefix, suffix = op[5], op[6]
                expect = op[7] if len(op) > 7 else 1
                block = prefix + content.rstrip("\n") + suffix
            path = os.path.join(root, dest)
            hay = read(path)
            strategy, spans = locate(hay, old)
            if not spans:
                return ("FAIL", dest, "锚点未找到（%s/%s:%s）：%r"
                        % (kind, op[1], op[2], old[:80].replace("\n", "\\n")))
            if expect != "all" and len(spans) != expect:
                return ("FAIL", dest, "锚点出现 %d 次，期望 %d 次（未修改）：%r"
                        % (len(spans), expect, old[:80].replace("\n", "\\n")))
            out = hay
            for (s, e, _, _) in reversed(spans):
                out = out[:s] + block + out[e:]
            write(path, out)
            return ("OK", dest, "%s ×%d [%s] (%s:%d)" % (kind, len(spans), strategy, op[1], op[2]))

        if kind in ("x_insert_after", "x_insert_before", "x_insert_after_f", "x_insert_before_f"):
            content, _ = extract_block(patch, op[2])
            dest = op[3]
            if kind.endswith("_f"):
                anchor, _ = extract_block(patch, op[4])
                expect = op[5] if len(op) > 5 else 1
            else:
                anchor = op[4]
                expect = op[5] if len(op) > 5 else 1
            path = os.path.join(root, dest)
            hay = read(path)
            strategy, spans = locate(hay, anchor)
            if not spans:
                return ("FAIL", dest, "锚点未找到（%s）：%r" % (kind, anchor[:80].replace("\n", "\\n")))
            if expect != "all" and len(spans) != expect:
                return ("FAIL", dest, "锚点出现 %d 次，期望 %d 次（未修改）：%r"
                        % (len(spans), expect, anchor[:80].replace("\n", "\\n")))
            out = hay
            block = content.rstrip("\n")
            for (s, e, _, _) in reversed(spans):
                if kind.startswith("x_insert_after"):
                    out = out[:e] + "\n" + block + out[e:]
                else:
                    out = out[:s] + block + "\n" + out[s:]
            write(path, out)
            return ("OK", dest, "%s ×%d [%s] (%s:%d)" % (kind, len(spans), strategy, op[1], op[2]))

        if kind == "x_delete":
            old, _ = extract_block(patch, op[2])
            dest = op[3]
            expect = op[4] if len(op) > 4 else 1
            path = os.path.join(root, dest)
            hay = read(path)
            strategy, spans = locate(hay, old)
            if not spans:
                return ("FAIL", dest, "锚点未找到（x_delete）")
            if expect != "all" and len(spans) != expect:
                return ("FAIL", dest, "锚点出现 %d 次，期望 %d 次" % (len(spans), expect))
            out = hay
            for (s, e, _, _) in reversed(spans):
                end = e + 1 if out[e:e + 1] == "\n" else e
                out = out[:s] + out[end:]
            write(path, out)
            return ("OK", dest, "x_delete ×%d" % len(spans))

    rel = op[1]
    path = os.path.join(root, rel)

    if kind == "create":
        content = op[2]
        if os.path.exists(path) or path in CACHE:
            return ("SKIP", rel, "文件已存在（create 跳过，避免覆盖已落盘内容）")
        write(path, content if content.endswith("\n") else content + "\n")
        return ("OK", rel, "create %d bytes" % len(content))

    if kind == "write":
        content = op[2]
        write(path, content if content.endswith("\n") else content + "\n")
        return ("OK", rel, "write %d bytes" % len(content))

    if not os.path.exists(path):
        return ("FAIL", rel, "目标文件不存在：%s" % rel)

    hay = read(path)

    if kind == "append":
        text = op[2]
        if not dry_run:
            write(path, hay + text)
        return ("OK", rel, "append %d bytes" % len(text))

    if kind in ("replace", "delete", "insert_after", "insert_before"):
        needle = op[2] if kind != "insert_after" and kind != "insert_before" else op[2]
        if kind == "replace":
            old, new = op[2], op[3]
            expect = op[4] if len(op) > 4 else 1
        elif kind == "delete":
            old, new = op[2], ""
            expect = op[3] if len(op) > 3 else 1
        elif kind == "insert_after":
            old, new = op[2], op[3]
            expect = op[4] if len(op) > 4 else 1
        else:
            old, new = op[2], op[3]
            expect = op[4] if len(op) > 4 else 1

        strategy, spans = locate(hay, old)
        if not spans:
            return ("FAIL", rel, "锚点未找到（%s）：%r" % (kind, old[:90].replace("\n", "\\n")))
        if expect != "all" and len(spans) != expect:
            return ("FAIL", rel,
                    "锚点出现 %d 次，期望 %d 次（不唯一，未修改）：%r"
                    % (len(spans), expect, old[:90].replace("\n", "\\n")))

        out = hay
        if kind == "replace":
            for (s, e, _, _) in reversed(spans):
                out = out[:s] + new + out[e:]
        elif kind == "delete":
            for (s, e, _, _) in reversed(spans):
                # 连同后面的换行一起删，避免留下空行
                end = e + 1 if out[e:e + 1] == "\n" else e
                out = out[:s] + out[end:]
        else:
            for (s, e, fl, ll) in reversed(spans):
                if kind == "insert_after":
                    # 取锚点最后一行的缩进作为插入块基准缩进
                    anchor_indent = indentation_of(hay, s) if (ll == fl) else indentation_of(hay, hay.rfind("\n", 0, e) + 1)
                    block = reindent(new, anchor_indent)
                    pos = e
                    out = out[:pos] + "\n" + block + out[pos:]
                else:
                    anchor_indent = indentation_of(hay, s)
                    block = reindent(new, anchor_indent)
                    out = out[:s] + block + "\n" + out[s:]

        if not dry_run:
            write(path, out)
        return ("OK", rel, "%s ×%d [%s]" % (kind, len(spans), strategy))

    return ("FAIL", rel, "未知操作：%s" % kind)


def reindent(block, indent):
    """原样插入（ops 文件里的缩进＝目标文件里的缩进，逐字节可控）。"""
    return block


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ops_file")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--root", default=os.getcwd())
    args = ap.parse_args()

    global DRY_RUN
    DRY_RUN = args.dry_run

    sys.path.insert(0, os.path.dirname(os.path.abspath(args.ops_file)))
    mod_name = os.path.splitext(os.path.basename(args.ops_file))[0]
    mod = __import__(mod_name)
    ops = mod.OPS

    results = []
    ok = skip = fail = 0
    for i, op in enumerate(ops):
        try:
            status, rel, detail = apply_op(args.root, op, args.dry_run)
        except Exception as exc:  # noqa
            status, rel, detail = "FAIL", op[1], "异常：%r" % (exc,)
        results.append({"i": i, "op": op[0], "file": rel, "status": status, "detail": detail})
        mark = {"OK": "  OK", "SKIP": "SKIP", "FAIL": "FAIL"}[status]
        print("[%s] #%02d %-13s %s | %s" % (mark, i, op[0], rel, detail))
        ok += status == "OK"
        skip += status == "SKIP"
        fail += status == "FAIL"

    print("\n=== %s: OK=%d SKIP=%d FAIL=%d (dry_run=%s) ==="
          % (mod_name, ok, skip, fail, args.dry_run))
    outdir = os.path.join(os.path.dirname(os.path.abspath(args.ops_file)), "..", "reports")
    os.makedirs(outdir, exist_ok=True)
    outfile = os.path.join(outdir, mod_name + ".json")
    with open(outfile, "w", encoding="utf-8") as f:
        json.dump({"ops_file": mod_name, "dry_run": args.dry_run,
                   "ok": ok, "skip": skip, "fail": fail, "results": results},
                  f, ensure_ascii=False, indent=2)
    print("report -> %s" % os.path.normpath(outfile))
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())
