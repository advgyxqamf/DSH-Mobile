#!/usr/bin/env python3
"""
严格校验 GitHub Actions workflow 文件。

============================================================================
 为什么需要这个脚本
============================================================================
 workflow 的错误有个共同特点：【发现得极晚】。一次构建动辄几十分钟
 到几小时，而这些错误本该在提交前几毫秒就发现。

 真实踩过的三类坑：

 1) 重复 key
    PyYAML 的 safe_load 对重复 key 是宽容的（静默保留最后一个），
    而 GitHub 的解析器是严格的 —— 重复 key 会让 workflow 加载失败，
    表现为 Actions 页面「红色 ✗ + 0 秒」，且【不产生任何步骤日志】。
    曾因注入步骤时误复制了 uses:/with: 行而中招。

    更隐蔽的是 on.push 写两次：后一个把前一个【静默覆盖】掉，
    触发条件完全不是你以为的样子。本仓库出现过「推任意 tag 都会
    误触发 APK 打包」，看起来毫无道理，根源就在这里。

 2) 本脚本默认校验【全部】workflow，而不是某一个
    这条是后补的：最初它只校验 build-apk.yml 自己，结果新建的
    workflow 出了重复 key 却没人管 —— 校验范围本身成了盲区。

 3) readelf 不加 -W
    默认模式在输出被重定向时会折行，按字段解析（awk $NF）会拿到
    错误值，校验步骤因此误报失败。本仓库在 pin-node 上踩过。

============================================================================
 用法
============================================================================
    python3 scripts/validate-workflow.py              # 校验全部
    python3 scripts/validate-workflow.py a.yml b.yml  # 校验指定文件
 退出码非 0 表示有问题，可直接用于 CI gate。
"""
import sys
import glob
import re
from pathlib import Path

import yaml


class StrictLoader(yaml.SafeLoader):
    """让重复 key 直接报错的 SafeLoader。"""


def _no_dup_keys(loader, node, deep=False):
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise yaml.constructor.ConstructorError(
                "while constructing a mapping", node.start_mark,
                "found duplicate key %r (GitHub rejects duplicate keys; "
                "PyYAML would silently keep the last one)" % (key,),
                key_node.start_mark)
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


StrictLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _no_dup_keys)


def get_on_block(doc):
    """取 on: 块。YAML 1.1 会把裸写的 on 解析成布尔 True，需兼容。"""
    for k in doc:
        if k is True or k == "on":
            return doc[k]
    return None


def check(path: Path):
    """返回 (errors, warns)。"""
    errs, warns = [], []
    src = path.read_text(encoding="utf-8")

    # ---- YAML 可解析 + 无重复 key ----
    try:
        doc = yaml.load(src, Loader=StrictLoader)
    except yaml.YAMLError as e:
        errs.append("YAML error:\n%s" % e)
        return errs, warns

    if not isinstance(doc, dict):
        errs.append("顶层不是映射（mapping）")
        return errs, warns

    # ---- 必须有 name ----
    # 没有 name 时，run 列表里会显示文件路径而不是业务名，
    # 在几十个 run 里找人极不方便。
    if "name" not in doc:
        warns.append("缺少顶层 name（run 列表里会显示文件路径）")

    # ---- on: 触发配置 ----
    on_block = get_on_block(doc)
    if on_block is None:
        errs.append("缺少 on: 触发配置")
    elif isinstance(on_block, dict):
        # 第二道防线：用原始文本再数一遍 on.push。
        # 严格 loader 已经能抓重复 key，但这里额外给出【可操作的说明】，
        # 因为重复 key 报错本身不会告诉你"两个 push 会互相覆盖"。
        m = re.search(r"^on:\n(.*?)(?=^\S|\Z)", src, re.M | re.S)
        if m:
            n_push = len(re.findall(r"^  push:", m.group(1), re.M))
            if n_push > 1:
                errs.append(
                    f"on.push 出现 {n_push} 次 —— 后一个会静默覆盖前一个，"
                    "触发条件将与预期不符。必须合并到同一个 push: 块。")

        push = on_block.get("push")
        if isinstance(push, dict):
            if "paths" in push and "paths-ignore" in push:
                errs.append("push 里同时写了 paths 与 paths-ignore，"
                            "语义冲突且行为不可预期")
            if not any(k in push for k in ("branches", "branches-ignore",
                                           "tags", "tags-ignore",
                                           "paths", "paths-ignore")):
                warns.append("push 没有任何过滤条件，会对所有推送触发")

    # ---- jobs 结构 ----
    jobs = doc.get("jobs")
    if not isinstance(jobs, dict) or not jobs:
        errs.append("没有定义任何 job")
        return errs, warns

    for jname, job in jobs.items():
        if not isinstance(job, dict):
            errs.append("job %r 不是映射" % jname)
            continue
        if "runs-on" not in job and "uses" not in job:
            errs.append("job %r 缺少 runs-on" % jname)
        tm = job.get("timeout-minutes")
        if tm is not None and not isinstance(tm, int):
            errs.append("job %r 的 timeout-minutes 必须是整数，实际是 %r"
                        % (jname, tm))
        steps = job.get("steps") or []
        if not steps:
            errs.append("job %r 没有 steps" % jname)
        seen = {}
        for i, s in enumerate(steps):
            if not isinstance(s, dict):
                errs.append("job %r 第 %d 个 step 不是映射" % (jname, i))
                continue
            if not ({"uses", "run"} & set(s)):
                errs.append("job %r 第 %d 个 step (%s) 既没有 uses 也没有 run"
                            % (jname, i, s.get("name")))
            if "uses" in s and "run" in s:
                errs.append("job %r 第 %d 个 step (%s) 同时有 uses 和 run"
                            % (jname, i, s.get("name")))
            n = s.get("name")
            if n:
                if n in seen:
                    errs.append("job %r 有重名 step %r（第 %d 和第 %d 个）"
                                % (jname, n, seen[n], i))
                seen[n] = i

    # ---- 文本级检查：readelf 必须带 -W ----
    # 不加 -W 时输出会折行，按字段解析拿到的是错值。详见文件头说明。
    if re.search(r"readelf\s+(?!-W\b)\S*\s*-l\b", src):
        warns.append("readelf 未加 -W：输出重定向时会折行，"
                     "按字段解析（awk $NF 等）会拿到错误值，导致校验误判")

    return errs, warns


def main(argv):
    if argv:
        files = [Path(a) for a in argv]
    else:
        files = sorted(Path(p) for p in glob.glob(".github/workflows/*.yml"))

    if not files:
        print("[warn] 没找到任何 workflow 文件")
        return 0

    n_err = n_warn = 0
    for f in files:
        if not f.exists():
            print("[ERROR] %s: 文件不存在" % f)
            n_err += 1
            continue
        errs, warns = check(f)
        if not errs and not warns:
            print("[ok] %s" % f)
            continue
        for e in errs:
            print("[ERROR] %s: %s" % (f, e))
            n_err += 1
        for w in warns:
            print("[WARN] %s: %s" % (f, w))
            n_warn += 1

    print()
    print("=== %d 个错误, %d 个警告 ===" % (n_err, n_warn))
    return 1 if n_err else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
